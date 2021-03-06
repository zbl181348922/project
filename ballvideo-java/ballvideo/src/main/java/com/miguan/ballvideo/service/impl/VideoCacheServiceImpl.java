package com.miguan.ballvideo.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.miguan.ballvideo.common.constants.Constant;
import com.miguan.ballvideo.common.constants.VideoContant;
import com.miguan.ballvideo.common.util.adv.AdvUtils;
import com.miguan.ballvideo.common.util.video.VideoUtils;
import com.miguan.ballvideo.mapper.*;
import com.miguan.ballvideo.redis.util.RedisKeyConstant;
import com.miguan.ballvideo.service.*;
import com.miguan.ballvideo.vo.AdvertVo;
import com.miguan.ballvideo.vo.FirstVideos;
import com.miguan.ballvideo.vo.SmallVideosVo;
import com.miguan.ballvideo.vo.VideosCatVo;
import com.miguan.ballvideo.vo.video.HotListVo;
import com.miguan.ballvideo.vo.video.Videos161Vo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author xujinbang
 * @date 2019/11/9.
 */
@Slf4j
@Service
public class VideoCacheServiceImpl implements VideoCacheService {

    @Resource(name="redisDB8Service")
    private RedisDB8Service redisDB8Service;
    @Resource
    private FirstVideosMapper firstVideosMapper;

    @Resource
    private SmallVideosMapper smallVideosMapper;

    @Resource
    private AdvertMapper advertMapper;

    @Resource
    private VideosCatMapper videosCatMapper;

    @Resource
    private AdvertOldService advertOldService;

    @Resource
    private MarketAuditService marketAuditService;

    @Resource
    private VideosCatService videosCatService;

    @Resource
    private ClUserVideosMapper clUserVideosMapper;

    @Resource
    private ClUserService clUserService;

    @Override
    public Map<Long, VideosCatVo> getVideosCatMap(String type) {
        List<VideosCatVo> videosCatVos = videosCatMapper.firstVideosCatList(type);
        if (CollectionUtils.isEmpty(videosCatVos)){
            return new HashMap<>();
        }
        return videosCatVos.stream().collect(Collectors.toMap(VideosCatVo::getId,v -> v));
    }

    @Override
    public void fillParams(List<Videos161Vo> firstVideos) {
        //VideoUtils.setLoveAndWatchNum(firstVideos);
        //???????????????????????????????????????
        VideoUtils.setCatName(firstVideos, null, getVideosCatMap(VideoContant.FIRST_VIDEO_CODE));
    }

    @Override
    public void getVideosCollection(List<Videos161Vo> firstVideos,String userId) {
        if (CollectionUtils.isEmpty(firstVideos)){
            return;
        }
        //???????????????????????????????????????????????????????????????????????????
        clUserService.packagingUserAndVideos(firstVideos);
        //?????????????????????????????????????????????
        if (userId != null && !userId.equals("0")) {
            List<Long> videoIds = firstVideos.stream().map(e -> e.getId()).collect(Collectors.toList());
            List<Long> list = clUserVideosMapper.queryCollection(userId, videoIds);
            for (int i = 0; i < list.size(); i++) {
                Long aLong =  list.get(i);
                for (Videos161Vo esVideo : firstVideos) {
                    Long videoId = esVideo.getId();
                    if (videoId.equals(aLong)){
                        esVideo.setCollection("1");
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void getVideosCollection2(List<HotListVo> hotList, String userId) {
        if (CollectionUtils.isEmpty(hotList)){
            return;
        }
        //???????????????????????????????????????????????????????????????????????????
        clUserService.packagingUserAndVideos2(hotList);
        //?????????????????????????????????????????????
        if (userId != null && !userId.equals("0")) {
            List<Long> videoIds = hotList.stream().map(e -> e.getId()).collect(Collectors.toList());
            List<Long> list = clUserVideosMapper.queryCollection(userId, videoIds);
            for (int i = 0; i < list.size(); i++) {
                Long aLong =  list.get(i);
                for (HotListVo esVideo : hotList) {
                    Long videoId = esVideo.getId();
                    if (videoId.equals(aLong)){
                        esVideo.setCollection("1");
                        break;
                    }
                }
            }
        }
    }
    /**
     * ?????????????????????????????????????????????????????????4????????????
     * @param params
     * @return
     */
    @Override
    public List<Videos161Vo> getFirstVideos161(Map<String, Object> params,int count) {
        final List<Videos161Vo> firstVideosList = new ArrayList<>();

        Object excludeId = params.get("excludeId");
        Object videoType = params.get("videoType");
        Object catId = params.get("catId");
        Object otherCatIds = params.get("otherCatIds");
        Object id = params.get("id");
        Object state = params.get("state");
        Object gatherIds = params.get("gatherIds");
        Object appPackage = params.get("appPackage");


        if(catId==null){
            //catId??????????????????????????? add shixh0430
            List<String> catIds = videosCatService.getCatIdsByStateAndType(Constant.open,VideoContant.FIRST_VIDEO_CODE);
            Collections.shuffle(catIds);
            catId = catIds.get(0);
            params.put("catId",catId);
        }

        final StringBuilder stringBuilder = new StringBuilder(RedisKeyConstant.NEWFIRSTVIDEO161_KEY);
        stringBuilder.append(excludeId != null ? excludeId.toString() : "@");
        stringBuilder.append(videoType != null ? videoType.toString() : "@");
        stringBuilder.append(catId != null ? catId.toString() : "@");
        stringBuilder.append(otherCatIds != null ? otherCatIds.toString() : "@");
        stringBuilder.append(id != null ? id.toString() : "@");
        stringBuilder.append(state != null ? state.toString() : "@");
        stringBuilder.append(gatherIds != null ? gatherIds.toString() : "@");
        stringBuilder.append(appPackage != null ? appPackage.toString() : "@");
        String key = stringBuilder.toString();

        if(!redisDB8Service.exits(key)) {
            List<Videos161Vo> list = firstVideosMapper.findFirstVideosList161(params);
            if(list.isEmpty()) {
                return firstVideosList;
            }
            String[] value = list.stream().collect(Collectors.toMap(Videos161Vo::getId,e -> JSONObject.toJSONString(e))).values().toArray(new String[list.size()]);
            redisDB8Service.sadd(key,RedisKeyConstant.NEWFIRSTVIDEO161_SECONDS,value);
        }
        redisDB8Service.randomValue(key,count).forEach(e -> firstVideosList.add(JSONObject.parseObject(e,Videos161Vo.class)));
        return firstVideosList;
    }

    /**
     * ?????????????????????????????????????????????????????????4????????????
     * @param params
     * @return
     */
    @Override
    public List<FirstVideos> getFirstVideos(Map<String, Object> params,int count) {
        List<FirstVideos> firstVideosList = new ArrayList<>();

        Object excludeId = params.get("excludeId");
        Object videoType = params.get("videoType");
        String catId = MapUtils.getString(params, "catId");
        Object otherCatIds = params.get("otherCatIds");
        Object id = params.get("id");
        Object state = params.get("state");
        Object appPackage = params.get("appPackage");

        if(StringUtils.isEmpty(catId)){
            //catId??????????????????????????? add shixh0430
            List<String> catIds = videosCatService.getCatIdsByStateAndType(Constant.open,VideoContant.FIRST_VIDEO_CODE);
            if(otherCatIds!=null && StringUtils.isNotEmpty(otherCatIds+"")){
                for(Long id_cat:(List<Long>)otherCatIds){
                    catIds.remove(String.valueOf(id_cat));
                }
            }
            if(CollectionUtils.isEmpty(catIds)){
                catId = "251";
            }else{
                Collections.shuffle(catIds);
                catId = catIds.get(0);
            }
            params.put("catId",catId);
        }
        final StringBuilder stringBuilder = new StringBuilder(RedisKeyConstant.NEWFIRSTVIDEO_KEY);
        stringBuilder.append(excludeId != null ? excludeId.toString() : "@");
        stringBuilder.append(videoType != null ? videoType.toString() : "@");
        stringBuilder.append(StringUtils.isNotEmpty(catId) ? catId : "@");
        stringBuilder.append(otherCatIds != null ? otherCatIds.toString() : "@");
        stringBuilder.append(id != null ? id.toString() : "@");
        stringBuilder.append(state != null ? state.toString() : "@");
        stringBuilder.append(appPackage != null ? appPackage.toString() : "@");
        String key = stringBuilder.toString();

        if(!redisDB8Service.exits(key)) {
            List<FirstVideos> list = firstVideosMapper.findFirstVideosList(params);
            if(list.isEmpty()) {
                return firstVideosList;
            }
            String[] value = list.stream().collect(Collectors.toMap(FirstVideos::getId,e -> JSONObject.toJSONString(e))).values().toArray(new String[list.size()]);
            redisDB8Service.sadd(key,RedisKeyConstant.NEWFIRSTVIDEO_SECONDS,value);
        }
        redisDB8Service.randomValue(key,count).forEach(e -> firstVideosList.add(JSONObject.parseObject(e,FirstVideos.class)));
        return firstVideosList;
    }

    /**
     * ?????????????????????2??????????????????????????????
     * @param param
     * @return
     */
    @Override
    public List<AdvertVo> getAdvertList(Map<String, Object> param,int count) {
        //??????2.2.0??????????????????????????????????????????????????????
        boolean isShield = marketAuditService.isShield(param);
        if (isShield) {
            return null;
        }
        List<AdvertVo> advers = advertMapper.queryAdertList(param);
        advers = advertOldService.getAdvertsByChannel(advers, param);
        return AdvUtils.computer(advers,count);
    }

    /**
     * ????????????????????????
     *
     * @param param
     * @return
     */
    @Override
    public List<AdvertVo> getBaseAdvertList(Map<String, Object> param) {
        //??????2.2.0??????????????????????????????????????????????????????
        boolean isShield = marketAuditService.isShield(param);
        if (isShield) {
            return null;
        }
        List<AdvertVo> advers = advertMapper.queryAdertList(param);
        advers = advertOldService.getAdvertsByChannel(advers, param);
        return advers;
    }

    /**
     * ?????????????????????????????????????????????????????????1????????????
     * @param params
     * @return
     */
    @Override
    public List<SmallVideosVo> getSmallVideos(Map<String, Object> params,int count) {
        return smallVideosMapper.findSmallVideosList(params);
    }


}


