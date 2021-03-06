package com.xiyou.speedvideo.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baidubce.services.vca.model.AnalyzeResponse;
import com.baidubce.services.vca.model.QueryResultResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiyou.speedvideo.dao.VideoMcaResultDao;
import com.xiyou.speedvideo.dto.BaiduLabelParams;
import com.xiyou.speedvideo.dto.LabelDto;
import com.xiyou.speedvideo.dto.VideoLabelDto;
import com.xiyou.speedvideo.entity.FirstVideos;
import com.xiyou.speedvideo.entity.FirstVideosMca;
import com.xiyou.speedvideo.entity.FirstVideosMcaResult;
import com.xiyou.speedvideo.entity.LabelUpLoadLog;
import com.xiyou.speedvideo.entity.mongo.VideoPaddleTag;
import com.xiyou.speedvideo.enums.LabelTypeEnum;
import com.xiyou.speedvideo.mapper.FirstVideosMapper;
import com.xiyou.speedvideo.mapper.FirstVideosMcaMapper;
import com.xiyou.speedvideo.mapper.FirstVideosMcaResultMapper;
import com.xiyou.speedvideo.service.FirstVideoService;
import com.xiyou.speedvideo.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.map.HashedMap;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.CriteriaDefinition;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * description:
 *
 * @author huangjx
 * @date 2020/10/14 4:28 ??????
 */
@Service
@Slf4j
public class FirstVideoServiceImpl implements FirstVideoService {

    @Resource
    private FirstVideosMapper firstVideosMapper;

    @Resource
    private FirstVideosMcaMapper firstVideosMcaMapper;

    @Resource
    private FirstVideosMcaResultMapper firstVideosMcaResultMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Resource
    private MongoTemplate mongoTemplate;

    @Value("${content-label-server.video-label}")
    private String contentLabelUrl;

    private static ExecutorService executor = new ThreadPoolExecutor(14, 20,
            60L, TimeUnit.SECONDS, new LinkedBlockingDeque<>());

    /**
     * ffmpeg??????????????????
     */
    private static final String tmpDir = "/usr/local/webserver/mca/shell";

    /**
     * ??????????????????
     */
    private static final String bsPrefix = "https://ss.bscstorage.com/xiyou-huangjunxian/speed-video/";

    /**
     * ??????????????????????????????
     * @param videos
     */
    private void doMcaApplyAction(List<BaiduLabelParams> videos) {
        for(BaiduLabelParams video: videos) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    log.info("??????????????????????????????(start)???"+ JSON.toJSONString(video));
                    AnalyzeResponse response = MCAUtil.analyzeMedia(video.getBsyUrl());
                    log.info("?????????????????????????????????????????????{}", response);
                    FirstVideosMcaResult result = new FirstVideosMcaResult(video.getVideoId(),video.getBsyUrl(),FirstVideosMcaResult.STATE_APPLYING,null,null,new Date());
                    firstVideosMcaResultMapper.insert(result);
                    log.info("??????????????????????????????(end)???"+ JSON.toJSONString(video));
                }
            });
        }
    }

    /**
     * ???????????????ai??????????????????
     * @param path
     * @param videoIds
     * @param limit
     * @param watchCount
     * @param minute
     * @param secondStart
     * @param secondEnd
     * @return
     */
    public String allInOneMethod(String path, String videoIds, Integer limit, Integer watchCount, Integer minute, Integer secondStart, Integer secondEnd) {
        // ??????????????????
        Map paramMap = new HashedMap();
        paramMap.put("videoIds",CommonUtil.string2List(videoIds,","));
        paramMap.put("excludeList",this.getMCAExistList());
        paramMap.put("limit",limit);
        paramMap.put("watchCount",watchCount);
        paramMap.put("minute",minute);
        paramMap.put("secondStart",secondStart);
        paramMap.put("secondEnd",secondEnd);
        List<FirstVideos> resultList = this.getDownloadList(paramMap);
        // ???????????????????????????
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
        }
        if(resultList.isEmpty()){
            return "error???????????????????????????";
        }
        //????????????????????????
        if(this.insertDownloading(resultList)){
            // ?????????????????????
            for (FirstVideos videos : resultList) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        //1???????????????
                        FirstVideosMca mca = downloadAndUpdate(videos,path);
                        log.info("?????????????????????"+ JSON.toJSONString(mca));
                        //2???FFMPEG
                        List<String> cmdList = new ArrayList<>();
                        String cmd = CommonUtil.getCmdByRule(mca);
                        if(cmd!=null){
                            cmdList.add(cmd);
                        }
                        mca.setState(FirstVideosMca.STATE_SPEED_COMPLETE);
                        //??????ffmpeg
                        List<FirstVideosMcaResult> resultList = speedAndUpdate(cmdList,mca);
                        log.info("?????????????????????"+ JSON.toJSONString(resultList));
                        //3???????????????
                        for(FirstVideosMcaResult result:resultList){
                            doUploadAndUpdateResult(result);
                            log.info("???????????????????????????"+ JSON.toJSONString(result));
                            //4?????????MCA
                            doApplyAction(result);
                            log.info("????????????MCA?????????"+ JSON.toJSONString(result));
                        }
                    }
                });
            }
        }
        return String.valueOf(resultList.size());
    }

    @Override
    public List<String> getMCAExistList(){
        List<String> resultList = firstVideosMapper.getMCAExistList();
        return resultList.size()==0?null:resultList;
    }

    @Override
    public List<FirstVideos> getDownloadList(Map<String, Object> paramMap){
        return firstVideosMapper.getDownloadList(paramMap);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public boolean insertDownloading(List<FirstVideos> downloadList){
        int result = firstVideosMapper.batchInsert2MCA(downloadList);
        return result>0;
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public FirstVideosMca downloadAndUpdate(FirstVideos firstVideos,String path){
        String filePath = DownloadURLFile.downloadFromUrl(firstVideos.getBsyUrl(), path,String.valueOf(firstVideos.getId()));
        FirstVideosMca firstVideosMca = new FirstVideosMca();
        firstVideosMca.setVideoId(firstVideos.getId());
        firstVideosMca.setLocalPath(filePath);
        firstVideosMca.setState(filePath!=null ? FirstVideosMca.STATE_DOWNLOAD_COMPLETE : FirstVideosMca.STATE_DOWNLOAD_ERROR);
        firstVideosMapper.updateMCAByVideoId(firstVideosMca);
        //??????firstVideosMca??????
        firstVideosMca = firstVideosMapper.selectMCAByVideoId(firstVideosMca);
        return firstVideosMca;
    }

    @Override
    public List<FirstVideosMcaResult> speedAndUpdate(List<String> cmdList,FirstVideosMca mca){
        List<FirstVideosMcaResult> resultList = new ArrayList<>();
        if(cmdList!=null && cmdList.size()>=0){
            // ??????????????????
            String tmpFilePrefix = tmpDir + "/" + System.currentTimeMillis() + new Random().nextInt(999);
            String inputTxt = tmpFilePrefix + "/speed.sh";
            FileUtils.mkdir(tmpFilePrefix);
            try {
                FileUtils.writeLineToFile(cmdList, inputTxt);
                String cmd = "sh "+inputTxt;
                System.out.println(cmd);
                FileUtils.execCmd(cmd);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // ???????????????????????????????????????????????????bin?????????
            FileUtils.deleteDir(tmpFilePrefix);
            resultList = doSpeedUpdate(cmdList,mca);
        }
        return resultList;
    }

    @Transactional(rollbackFor=Exception.class)
    public List<FirstVideosMcaResult> doSpeedUpdate(List<String> cmdList,FirstVideosMca mca){
        List<FirstVideosMcaResult> resultList = new ArrayList<>();
        //????????????
        if(firstVideosMapper.updateMCAByVideoId(mca)>0){
            //????????????????????????????????????????????????
//            FirstVideosMcaResult result = new FirstVideosMcaResult(mca.getVideoId(),mca.getBsyUrl(),FirstVideosMcaResult.STATE_WAIT_APPLY,mca.getLocalPath(),1,new Date());
//            firstVideosMcaResultMapper.insert(result);
//            resultList.add(result);
            //????????????
            for(String cmd:cmdList){
                Integer speed = CommonUtil.getSpeed(cmd);
                String localPath = CommonUtil.getSpeedLocalPath(cmd);
                FirstVideosMcaResult result = new FirstVideosMcaResult(mca.getVideoId(),null,FirstVideosMcaResult.STATE_WAIT_UPLOAD,localPath,speed,new Date());
                firstVideosMcaResultMapper.insert(result);
                resultList.add(result);
            }
        }
        return resultList;
    }

    @Override
    public List<FirstVideosMca> getDownloadCompleteList() {
        QueryWrapper<FirstVideosMca> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("state",1);
        return firstVideosMcaMapper.selectList(queryWrapper);
    }

    @Override
    public List<FirstVideosMcaResult> getWaitUploadList(){
        QueryWrapper<FirstVideosMcaResult> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("state",FirstVideosMcaResult.STATE_WAIT_UPLOAD);
        return firstVideosMcaResultMapper.selectList(queryWrapper);
    }

    @Override
    public List<FirstVideosMcaResult> getWaitApplyList(){
        QueryWrapper<FirstVideosMcaResult> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("state",FirstVideosMcaResult.STATE_WAIT_APPLY);
        return firstVideosMcaResultMapper.selectList(queryWrapper);
    }

    @Override
    public void doUploadAndUpdateResult(FirstVideosMcaResult result){
        BSCloudUtil.uploadFile(result.getLocalPath());
        String fileName = DownloadURLFile.getFileNameFromUrl(result.getLocalPath());
        result.setBsyUrl(bsPrefix+fileName);
        result.setUpdateAt(new Date());
        result.setState(FirstVideosMcaResult.STATE_WAIT_APPLY);
        if(firstVideosMcaResultMapper.updateById(result)>0){
            //??????????????????
//            deleteVideo(result);
        }
    }

    @Override
    public void doResultSave(String param){
        //??????taskID ??????????????????
//        JSONObject jsonObject = JSON.parseObject(param);
//        String taskId = String.valueOf(jsonObject.get("taskId"));
        QueryResultResponse response = JSON.parseObject(param,QueryResultResponse.class);
        String status = response.getStatus();
        if ("FINISHED".equals(status)) {
            // ????????????url???????????????
            String source = response.getSource();
            FirstVideosMcaResult result = new FirstVideosMcaResult();
            result.setState(FirstVideosMcaResult.STATE_APPLY_COMPLETE);
            result.setBsyUrl(source);
            result.setResult(param);
            if(firstVideosMapper.updateMCAResultBySource(result)>0){
                // ????????????????????????mongodb
//                QueryWrapper<FirstVideosMcaResult> queryWrapper = new QueryWrapper<>();
//                queryWrapper.eq("bsy_url",source);
//                result = firstVideosMcaResultMapper.selectOne(queryWrapper);
//                deleteVideo(result);
//                VideoMcaResult exist = videoMcaResultDao.findByVideoId(result.getVideoId());
//                if(exist==null){
//                    VideoMcaResult videoMcaResult = MCAUtil.parsingMongoResult(param);
//                    videoMcaResult.setVideoId(result.getVideoId());
//                    videoMcaResultDao.saveVideoMcaResult(videoMcaResult);
//                }
                labelUpLoad(null,source, param, 1);  //??????????????????????????????
            }
        }
    }

    /**
     * ??????????????????????????????
     * @param bsyUrl ????????????
     * @param jsonResult ????????????json
     * @param type ?????????1--?????????AI???2--????????????
     */
    public void labelUpLoad(Integer videoId, String bsyUrl, String jsonResult, int type) {
        log.info("??????????????????????????????,videoId:{},bsyUrl:{}", videoId, bsyUrl);
        VideoLabelDto dto = new VideoLabelDto();
        if(videoId == null) {
            videoId = firstVideosMapper.getMcaVideoId(bsyUrl);  //???????????????????????? ??????id
        }
        dto.setVideoId(videoId);
        dto.setResultType(1);
        dto.setResult("??????????????????");
        List<LabelDto> tags = new ArrayList<>();  //????????????
        if(type == 1) {
            //?????????AI
            tags = this.parseLabelJson(jsonResult, 5);
            dto.setSource("BAIDUAI");
        } else {
            //??????
            tags = this.parseAlgorithmLabelJson(jsonResult, 5);
            dto.setSource("ALGORITHM");
        }
        dto.setTags(tags);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<String>(JSON.toJSONString(dto), headers);
        String result = null;
        try {
            result = restTemplate.postForObject(contentLabelUrl, request, String.class);
        } catch (Exception e) {
            log.error("????????????????????????", e.getMessage());
            log.error("??????????????????error,videoId:{},bsyUrl:{},jsonResult:{}", videoId, bsyUrl,jsonResult);
        }
        LabelUpLoadLog labelUpLoadLog = new LabelUpLoadLog(videoId, bsyUrl, type, JSON.toJSONString(dto), result);
        firstVideosMapper.insertLabelUploadLog(labelUpLoadLog);
    }

    /**
     * ??????????????????
     * @param result
     * @return
     */
    private String getLabel(String result) {
        JSONObject jsonObject = JSONObject.parseObject(result);
        JSONArray resultsJson = jsonObject.getJSONArray("results");
        List<String> labelList = new ArrayList<>();
        for(int i=0;i<resultsJson.size();i++) {
            JSONArray resultJson = resultsJson.getJSONObject(i).getJSONArray("result");
            for(int j=0;j<resultJson.size();j++) {
                String label = resultJson.getJSONObject(j).getString("attribute");
                if(!StringUtils.isEmpty(label)) {
                    labelList.add(label);
                }
            }
        }
        return String.join(",", labelList);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void doApplyAction(FirstVideosMcaResult result){
        MCAUtil.analyzeMedia(result.getBsyUrl());
        //???????????????
        result.setState(FirstVideosMcaResult.STATE_APPLYING);
        result.setUpdateAt(new Date());
        firstVideosMcaResultMapper.updateById(result);
        //??????????????????
        deleteVideo(result);
    }

    /**
     * ????????????
     * @param result
     */
    private void deleteVideo(FirstVideosMcaResult result){
        //??????????????????
        String rmShell = "rm -rf "+result.getLocalPath();
        log.info("??????????????????:"+rmShell);
        FileUtils.execCmd(rmShell);
        //???????????????
        rmShell = "rm -rf "+CommonUtil.speed2LocalPath(result.getLocalPath());
        log.info("???????????????:"+rmShell);
        FileUtils.execCmd(rmShell);
    }

    @Override
    public List<FirstVideosMcaResult> getMCAResult(Map<String, Object> paramMap) {
        return firstVideosMapper.getMCAResult(paramMap);
    }

    /**
     * ????????????AI??????????????????
     * @param videoIds ??????id????????????????????????
     * @return
     */
    public List<VideoLabelDto> getAlgorithmLabelResult(List<Integer> videoIds) {
        List<VideoPaddleTag> completeVideos = findPaddleTagByVideoId(videoIds);
        List<Integer> completeVideoIds = completeVideos.stream().map(r->r.getVideo_id()).collect(Collectors.toList());  //????????????????????????id;
        videoIds.removeAll(completeVideoIds);  //??????????????????

        //?????????????????????????????????
        List<VideoLabelDto> videoLabelList = new ArrayList<>();
        for(VideoPaddleTag videoPaddleTag:completeVideos) {
            VideoLabelDto videoLabelDto = new VideoLabelDto();
            videoLabelDto.setVideoId(videoPaddleTag.getVideo_id());
            videoLabelDto.setResultType(1);
            videoLabelDto.setSource("ALGORITHM");
            videoLabelDto.setResult("??????????????????");
            videoLabelDto.setTags(this.parseAlgorithmLabelJson(videoPaddleTag.getFull_label(), 0));
            videoLabelList.add(videoLabelDto);
        }

        //????????????????????????
        for(Integer videoId:videoIds) {
            VideoLabelDto videoLabelDto = new VideoLabelDto();
            videoLabelDto.setVideoId(videoId);
            videoLabelDto.setResultType(0);
            videoLabelDto.setSource("ALGORITHM");
            videoLabelDto.setResult("???????????????");
            videoLabelList.add(videoLabelDto);
        }
        return videoLabelList;
    }

    /**
     * ???????????????AI??????????????????,??????????????????????????????????????????
     * @param blParams
     * @return
     */
    public List<VideoLabelDto> getBaiduLabelResult(List<BaiduLabelParams> blParams) {
        if(blParams == null || blParams.isEmpty()) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("videoList", blParams);

        List<FirstVideosMcaResult> completeVideos = this.firstVideosMapper.findVideoMcaResult(params); //????????????id?????????url????????????????????????????????????
        List<String> completeVideoIds = completeVideos.stream().map(r->String.valueOf(r.getVideoId())).collect(Collectors.toList());  //????????????????????????id
//        videoList.removeAll(completeVideoIds); //??????????????????ai???????????????IE??????
        List<BaiduLabelParams> noCompleteVideos = blParams.stream().filter(r->!completeVideoIds.contains(String.valueOf(r.getVideoId()))).collect(Collectors.toList());

        //?????????????????????????????????
        List<VideoLabelDto> videoLabelList = new ArrayList<>();
        for(FirstVideosMcaResult mcaResult:completeVideos) {
            VideoLabelDto videoLabelDto = new VideoLabelDto();
            videoLabelDto.setVideoId(mcaResult.getVideoId());
            videoLabelDto.setResultType(1);
            videoLabelDto.setSource("BAIDUAI");
            videoLabelDto.setResult("??????????????????");
            videoLabelDto.setTags(this.parseLabelJson(mcaResult.getResult(), 0));
            videoLabelList.add(videoLabelDto);
        }

        //????????????????????????
        for(BaiduLabelParams video:noCompleteVideos) {
            VideoLabelDto videoLabelDto = new VideoLabelDto();
            videoLabelDto.setVideoId(video.getVideoId());
            videoLabelDto.setResultType(0);
            videoLabelDto.setSource("BAIDUAI");
            videoLabelDto.setResult("???????????????");
            videoLabelList.add(videoLabelDto);
        }
        if(noCompleteVideos != null && !noCompleteVideos.isEmpty()) {
            //??????????????????
            doMcaApplyAction(noCompleteVideos);
        }
        return videoLabelList;
    }

    /**
     * ?????????????????????json????????????????????????????????????????????????
     * @param labelJsonStr
     * @param index ???????????????????????????index?????????????????????0??????????????????
     * @return
     */
    public List<LabelDto> parseLabelJson(String labelJsonStr, int index) {
        List<LabelDto> tags = new ArrayList<>();  //????????????
        try {
            JSONObject labelJson = JSONObject.parseObject(labelJsonStr);
            JSONArray labelArray = labelJson.getJSONArray("results");
            for(int i=0;i < labelArray.size();i++) {
                JSONObject labelType = labelArray.getJSONObject(i);
                String type = labelType.getString("type");
                JSONArray result = labelType.getJSONArray("result");
                //?????????????????????????????????????????????
                if(LabelTypeEnum.figure.getCode().equals(type) || LabelTypeEnum.scenario.getCode().equals(type) || LabelTypeEnum.knowledgeGraph.getCode().equals(type) ) {
                    for(int j=0;j<result.size();j++) {
                        JSONObject oneLabel = result.getJSONObject(j);
                        LabelDto labelDto = new LabelDto();
                        labelDto.setValue(oneLabel.getString("attribute")); //?????????
                        labelDto.setScore(oneLabel.getDouble("confidence"));  //?????????
                        labelDto.setType(LabelTypeEnum.getLabelType(type));
                        tags.add(labelDto);
                    }
                }
            }
        } catch (Exception e) {
            log.error("????????????ai????????????????????????json??????", e);
        }

        tags = tags.stream().sorted(Comparator.comparing(LabelDto::getScore).reversed()).collect(Collectors.toList());
        if(index > 0) {
            //????????????????????????
            tags = tags.subList(0, (tags.size() > index ? index : tags.size()));
        }
        return tags;
    }

    /**
     * ??????????????????????????????????????????
     */
    public void upLoadHistoryVideoLabel(Integer startRow) {
        int pageSize = 1000;  //???????????????

        Map<String, Object> params = new HashMap<>();
        params.put("startRow", startRow);
        params.put("pageSize", pageSize);
        List<FirstVideosMcaResult> mcaResults = firstVideosMapper.findVideoMcaHisResult(params);

        while(mcaResults != null && !mcaResults.isEmpty()) {
            for(FirstVideosMcaResult video : mcaResults) {
                labelUpLoad(video.getVideoId(), null, video.getResult(), 1);  //??????????????????????????????
            }

            log.info("??????????????????????????????????????????,startRow:{}, pageSize:{}", startRow, pageSize);
            startRow+=pageSize;
            params.put("startRow", startRow);
            mcaResults = firstVideosMapper.findVideoMcaHisResult(params);
        }
    }

    /**
     * ???????????????????????????????????????
     */
    public void uploadHistoryAlgorithmLabel(Integer startRow) {
        int pageSize = 100;  //???????????????
        List<VideoPaddleTag> paddleTags = this.findVideoPaddleTag(startRow, pageSize);

        while(paddleTags != null && !paddleTags.isEmpty()) {

            for(VideoPaddleTag video : paddleTags) {
                labelUpLoad(video.getVideo_id(), null, video.getFull_label(), 2);  //??????????????????????????????
            }

            log.info("???????????????????????????????????????,startRow:{}, pageSize:{},total:{}", startRow, pageSize);
            startRow+=pageSize;
            paddleTags = this.findVideoPaddleTag(startRow, pageSize);
        }

    }

    /**
     * ???????????????????????????json????????????????????????????????????????????????
     * @param labelJsonStr
     * @param index ???????????????????????????index?????????????????????0??????????????????
     * @return
     */
    private List<LabelDto> parseAlgorithmLabelJson(String labelJsonStr, int index) {
        List<LabelDto> tags = new ArrayList<>();  //????????????
        try {
            JSONArray labelArray = JSONObject.parseArray(labelJsonStr);
            for(int i=0;i<labelArray.size();i++) {
                JSONObject oneLabel = labelArray.getJSONObject(i);
                LabelDto labelDto = new LabelDto();
                labelDto.setType(LabelTypeEnum.keyword.getType());
                labelDto.setScore(oneLabel.getDouble("probability") * 100);
                labelDto.setValue(oneLabel.getString("class_name"));
                tags.add(labelDto);
            }
        } catch (Exception e) {
            log.error("??????????????????????????????json??????", e);
        }

        tags = tags.stream().sorted(Comparator.comparing(LabelDto::getScore).reversed()).collect(Collectors.toList());
        if(index > 0) {
            //????????????????????????
            tags = tags.subList(0, (tags.size() > index ? index : tags.size()));
        }
        return tags;
    }

    /**
     * ?????????mongodb???????????????????????????
     * @param startRow
     * @param pageSize
     * @return
     */
    private List<VideoPaddleTag> findVideoPaddleTag(Integer startRow, Integer pageSize) {
        Query query = new Query();
        query.skip(startRow);
        query.limit(pageSize);
        List<LinkedHashMap> list = mongoTemplate.find(query, LinkedHashMap.class, "video_paddle_tag");

        List<VideoPaddleTag> result = new ArrayList<>();
        for(LinkedHashMap map : list) {
            VideoPaddleTag tag = new VideoPaddleTag();
            tag.setUrl(String.valueOf(map.get("url")));
            tag.setVideo_id(Integer.parseInt(String.valueOf(map.get("video_id"))));
            tag.setFull_label(JSONObject.toJSONString(map.get("full_label")));
            result.add(tag);
        }
        return result;
    }

    /**
     * ????????????id??????????????????????????????
     * @param videoIds
     * @return
     */
    private List<VideoPaddleTag> findPaddleTagByVideoId(List<Integer> videoIds) {
        Query query = new Query();
        query.addCriteria(Criteria.where("video_id").in(videoIds));
        List<LinkedHashMap> list = mongoTemplate.find(query, LinkedHashMap.class, "video_paddle_tag");
        List<VideoPaddleTag> result = new ArrayList<>();
        for(LinkedHashMap map : list) {
            VideoPaddleTag tag = new VideoPaddleTag();
            tag.setUrl(String.valueOf(map.get("url")));
            tag.setVideo_id(Integer.parseInt(String.valueOf(map.get("video_id"))));
            tag.setFull_label(JSONObject.toJSONString(map.get("full_label")));
            result.add(tag);
        }
        return result;
    }
}
