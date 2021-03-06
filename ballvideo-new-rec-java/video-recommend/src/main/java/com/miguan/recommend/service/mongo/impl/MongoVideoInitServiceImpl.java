package com.miguan.recommend.service.mongo.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.miguan.recommend.bo.VideoInitDto;
import com.miguan.recommend.common.constants.MongoConstants;
import com.miguan.recommend.common.constants.RabbitMqConstants;
import com.miguan.recommend.common.constants.SymbolConstants;
import com.miguan.recommend.common.util.DateUtil;
import com.miguan.recommend.common.util.TimeUtil;
import com.miguan.recommend.entity.es.VideoEmbeddingEs;
import com.miguan.recommend.entity.mongo.*;
import com.miguan.recommend.mapper.FirstVideosMapper;
import com.miguan.recommend.service.es.EsVideoInitService;
import com.miguan.recommend.service.mongo.MongoVideoInitService;
import com.miguan.recommend.service.recommend.EmbeddingService;
import com.miguan.recommend.service.xy.AblumService;
import com.miguan.recommend.vo.RecVideosVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import tool.util.StringUtil;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isEmpty;

@Slf4j
@Service
public class MongoVideoInitServiceImpl implements MongoVideoInitService {

    @Resource
    private FirstVideosMapper firstVideosMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource(name = "logMongoTemplate")
    private MongoTemplate logMongoTemplate;
    @Resource(name = "featureMongoTemplate")
    private MongoTemplate featureMongoTemplate;
    @Resource(name = "recDB9Pool")
    private JedisPool recDB9Pool;
    @Resource
    private AblumService ablumService;
    @Resource
    private EmbeddingService embeddingService;
    @Resource
    private EsVideoInitService esVideoInitService;

    /**
     * ?????????mongodb????????????(????????????????????????????????????????????????????????????)
     */
    @Override
    public void hotspotInit() {
        // ??????????????????
        int count = firstVideosMapper.count();
        if (count == 0) {
            return;
        }
        log.debug("??????{}????????????????????????", count);
        deleteAllHotspot();  //????????????????????????????????????
        deleteAllIncentiveHotspot();  //????????????????????????????????????

        int loop = 0;
        int size = 5000;
        while (true) {
            VideoInitDto dto = new VideoInitDto();
            dto.setType(VideoInitDto.init_video);
            dto.setSkip(loop);
            dto.setSize(size);
            rabbitTemplate.convertAndSend(RabbitMqConstants.HOTSPOST_INIT_EXCHANGE, RabbitMqConstants.HOTSPOST_INIT_RUTE_KEY, JSONObject.toJSONString(dto));
            if (loop >= count) {
                break;
            }
            loop = loop + size;
        }
    }

    @Override
    public void sendWashHotspotVideoMsgToMQ() {
        // ??????????????????
        int count = firstVideosMapper.count();
        if (count == 0) {
            return;
        }
        log.debug("??????{}?????????????????????", count);
        int loop = 0;
        int size = 5000;
        while (true) {
            VideoInitDto dto = new VideoInitDto();
            dto.setType(VideoInitDto.wash_hotpots_video);
            dto.setSkip(loop);
            dto.setSize(size);
            rabbitTemplate.convertAndSend(RabbitMqConstants.HOTSPOST_INIT_EXCHANGE, RabbitMqConstants.HOTSPOST_INIT_RUTE_KEY, JSONObject.toJSONString(dto));
            if (loop >= count) {
                break;
            }
            loop = loop + size;
        }
    }

    /**
     * ?????????????????????????????????MQ
     */
    @Override
    public void sendWashIncentiveVideoMsgToMQ() {
        VideoInitDto dto = new VideoInitDto();
        dto.setType(VideoInitDto.wash_incentive_video);
        rabbitTemplate.convertAndSend(RabbitMqConstants.HOTSPOST_INIT_EXCHANGE, RabbitMqConstants.HOTSPOST_INIT_RUTE_KEY, JSONObject.toJSONString(dto));
    }

    /**
     * ??????????????????
     */
    @Override
    public void doWashIncentiveVideo(VideoInitDto initDto) {
        log.warn("=============================????????????????????????=============================");
        List<RecVideosVo> videos = firstVideosMapper.findIncentiveVideo();
        List<String> ids = videos.stream().map(e -> e.getId().toString()).collect(Collectors.toList());
        //???????????????????????????????????????
        Query query = new Query(Criteria.where("video_id").nin(ids));
        logMongoTemplate.updateMulti(query, Update.update("state", 0).set("update_at", new Date()), MongoConstants.incentive_video_hotspot);
        String weights = null;
        String weights1 = null;
        //  ???redis?????????????????????
        try (Jedis jedis = recDB9Pool.getResource()) {
            weights = jedis.get("bg_videoCount:allVpr");
            weights1 = jedis.get("bg_videoCount:allVptr");
        }
        Double weight = StringUtils.isBlank(weights) ? Double.valueOf(0) : Double.valueOf(weights);
        Double weight1 = StringUtils.isBlank(weights1) ? Double.valueOf(0) : Double.valueOf(weights1);
        //???????????????????????????????????????????????????
        videos.forEach(e -> {
            this.addOrUpdateHotspot(e, weight, weight1);
        });
        log.warn("=============================????????????????????????=============================");
    }

    @Override
    public void doWashHotspotVideo(VideoInitDto initDto) {
        log.info("?????????????????????skip>>{}, size>>{}", initDto.getSkip(), initDto.getSize());
        List<RecVideosVo> recVideosVoList = firstVideosMapper.findInPage(initDto.getSkip(), initDto.getSize());
        //  ???redis?????????????????????
        String allVpr = null;
        String allVptr = null;
        try (Jedis jedis = recDB9Pool.getResource()) {
            allVpr = jedis.get("bg_videoCount:allVpr");
            allVptr = jedis.get("bg_videoCount:allVptr");
        }
        Double weights = isEmpty(allVpr) ? 0.0D : Double.parseDouble(allVpr);
        Double weights1 = isEmpty(allVptr) ? 0.0D : Double.parseDouble(allVptr);
        recVideosVoList.forEach(v -> {
            this.addOrUpdateHotspot(v, weights, weights1);
        });

    }

    /**
     * ????????????????????????????????????
     */
    @Override
    public void deleteAllHotspot() {
        Query query = new Query();
        logMongoTemplate.remove(query, MongoConstants.video_hotspot);
    }

    /**
     * ????????????????????????????????????
     */
    @Override
    public void deleteAllIncentiveHotspot() {
        Query query = new Query();
        logMongoTemplate.remove(query, MongoConstants.incentive_video_hotspot);
    }

    @Override
    public void updateHotspot(String videoIds, boolean isPopUpOnline) {
        String weights = null;
        String weights1 = null;
        if(isPopUpOnline){
            weights = "0.91";
            weights1 = "1200";
        } else {
            //  ???redis?????????????????????
            try (Jedis jedis = recDB9Pool.getResource()) {
                weights = jedis.get("bg_videoCount:allVpr");
                weights1 = jedis.get("bg_videoCount:allVptr");
            }
        }
        Double weight = StringUtils.isBlank(weights) ? Double.valueOf(0) : Double.valueOf(weights);
        Double weight1 = StringUtils.isBlank(weights1) ? Double.valueOf(0) : Double.valueOf(weights1);

        List<Integer> videoIdList = Stream.of(videoIds.split(SymbolConstants.comma)).map(Integer::valueOf).collect(Collectors.toList());
        List<RecVideosVo> recVideosVoList = firstVideosMapper.findByIds(videoIdList);

        recVideosVoList.forEach(e -> {
            this.addOrUpdateHotspot(e, weight, weight1);
            VideoEmbeddingEs video = embeddingService.getVideoEmbeddingVector(String.valueOf(e.getId()), e.getVideoUrl());
            double videoSize = StringUtil.isEmpty(e.getVideoSize()) ? 0D : Double.parseDouble(e.getVideoSize()); //????????????
            if (video == null && videoSize < 100) {
                //???????????????????????????????????????????????????100M????????????????????????
                log.info("??????????????????");
                embeddingService.videoEmbedding(e);  //??????????????????????????????????????????????????????????????????
            }
            // ????????????Es???????????????????????????
            esVideoInitService.videoTitleInit(e);
            //ES?????????????????????????????????
            embeddingService.saveEsImgVector(e.getId().intValue(), e.getTitle(), e.getBsyImgUrl());
        });
    }

    @Override
    public void updateHotspot(String videoId, double weights, double weights1) {
        RecVideosVo recVideosVo = null;
        if("undefined".equals(videoId) || "0".equals(videoId)){
            log.error("MQ????????????videoID???undefined");
            return;
        }
        try{
            recVideosVo = firstVideosMapper.findById(Integer.parseInt(videoId));
        }catch (Exception e){
            log.error("????????????ID?????????????????????",e);
        }
        if(recVideosVo == null){
            return;
        }
        this.addOrUpdateHotspot(recVideosVo, weights, weights1);
        double videoSize = StringUtil.isEmpty(recVideosVo.getVideoSize()) ? 0D : Double.parseDouble(recVideosVo.getVideoSize()); //????????????
        if (recVideosVo == null && videoSize < 100) {
            //???????????????????????????????????????????????????100M????????????????????????
            log.info("??????????????????");
            embeddingService.videoEmbedding(recVideosVo);  //??????????????????????????????????????????????????????????????????
        }
        // ????????????Es???????????????????????????
        esVideoInitService.videoTitleInit(recVideosVo);
    }

    @Override
    public void updateHotspotState(String videoIds, Integer state) {
        log.debug("????????????[{}]????????????>>{}", videoIds, state);
        String[] ids = videoIds.split(",");
        for (String id : ids) {
            Query query = new Query(Criteria.where("video_id").is(id));
            Update update = new Update();
            update.set("state", state);
            logMongoTemplate.updateMulti(query, update, VideoHotspotVo.class, MongoConstants.video_hotspot);
            logMongoTemplate.updateMulti(query, update, IncentiveVideoHotspot.class, MongoConstants.incentive_video_hotspot);
            embeddingService.deleteVideoEmbedding(id);  //??????es????????????????????????
        }
        // ????????????Es???????????????????????????
        esVideoInitService.batchVideoTitleInitById(Lists.newArrayList(ids));
    }

    @Override
    public void updateHotspotAlbumState(String videoIds) {
        log.debug("????????????[{}]??????????????????", videoIds);
        String[] ids = videoIds.split(",");
        for (String id : ids) {
            Query query = new Query(Criteria.where("video_id").is(id));
            Update update = new Update();
            update.set("album_id", ablumService.getVideoAblumId(id));
            logMongoTemplate.updateMulti(query, update, VideoHotspotVo.class, MongoConstants.video_hotspot);
            logMongoTemplate.updateMulti(query, update, IncentiveVideoHotspot.class, MongoConstants.incentive_video_hotspot);
        }
    }

    @Override
    public void initScore() {
        log.warn("=============================???????????????????????????=============================");
        Query query = new Query();
        Update update = new Update();
        update.set("score", 0);
        update.set("is_score", 0);
        logMongoTemplate.updateMulti(query, update, MongoConstants.video_hotspot);
        log.debug("??????????????????");
        Query heahCountQury = new Query();
        long headCount = logMongoTemplate.count(heahCountQury, MongoConstants.head_recommend_video);
        log.debug("??????{}??????????????????????????????", headCount);
        int size = 100;
        long theadNum = headCount % size > 0 ? (headCount / size) + 1 : headCount / size;

        for (int i = 0; i < theadNum; i++) {
            this.initScoreSplit(size * i, size);
        }
        log.warn("=============================???????????????????????????=============================");
    }

    @Override
    public void sendVideoTagTop5IdsToMQ() {
        Query query = new Query();
        Long count = featureMongoTemplate.count(query, MongoConstants.video_paddle_tag);
        if (count < 1) {
            return;
        }

        int skip = 0;
        int limit = 3000;
        while (skip < count) {
            VideoInitDto dto = new VideoInitDto();
            dto.setType(VideoInitDto.video_paddle_tag_top5);
            dto.setSkip(skip);
            dto.setSize(limit);
            rabbitTemplate.convertAndSend(RabbitMqConstants.HOTSPOST_INIT_EXCHANGE, RabbitMqConstants.HOTSPOST_INIT_RUTE_KEY, JSONObject.toJSONString(dto));
            skip += limit;
        }
    }

    @Override
    public void doVideoTagTop5Ids(VideoInitDto initDto) {
        log.info("video tag top 5 :{}", JSONObject.toJSONString(initDto));
        Query query = new Query();
        query.with(Sort.by(Sort.Order.asc("video_id")));
        query.skip(initDto.getSkip());
        query.limit(initDto.getSize());
        log.info("video tag top 5 ????????????:{}", query.toString());
        List<VideoPaddleTag> videoPaddleTagList = featureMongoTemplate.find(query, VideoPaddleTag.class, MongoConstants.video_paddle_tag);

        if (CollectionUtils.isEmpty(videoPaddleTagList)) {
            log.info("video tag top 5 ?????????????????????");
            return;
        }

        for (VideoPaddleTag paddleTag : videoPaddleTagList) {
            List<FullLable> top5Ids = paddleTag.getFull_lable().stream().sorted((t1, t2)->{
                return t2.getProbability().compareTo(t1.getProbability());
            }).limit(5).collect(Collectors.toList());
            log.info("video tag top 5 ??????:{}>>{}", paddleTag.getVideo_id(), JSONObject.toJSONString(top5Ids));
            this.doUpdateVideoTagTop5Ids(paddleTag.getVideo_id().toString(), top5Ids);
        }
        videoPaddleTagList.clear();
    }

    @Override
    public void updateVideoTagTop5Ids(Integer videoId) {
        List<FullLable> top5Ids = this.getVideoPaddleTagTop5(videoId);
        this.doUpdateVideoTagTop5Ids(videoId.toString(), top5Ids);
    }

    private void doUpdateVideoTagTop5Ids(String videoId, List<FullLable> top5Ids){
        if(CollectionUtils.isEmpty(top5Ids)){
            return;
        }
        Query top5Quer = new Query();
        top5Quer.addCriteria(Criteria.where("video_id").is(videoId));
        Update update = new Update();
        update.set("top5_ids", top5Ids);
        logMongoTemplate.updateMulti(top5Quer, update, MongoConstants.video_hotspot);
    }

    private List<FullLable> getVideoPaddleTagTop5(Integer videoId){
        Query query = new Query();
        query.addCriteria(Criteria.where("video_id").is(videoId));
        List<VideoPaddleTag> paddleTagList = featureMongoTemplate.find(query, VideoPaddleTag.class, MongoConstants.video_paddle_tag);
        if(CollectionUtils.isEmpty(paddleTagList)){
            return null;
        }

        List<FullLable> top5Ids = paddleTagList.get(0).getFull_lable().stream().sorted((t1, t2)->{
            return t2.getProbability().compareTo(t1.getProbability());
        }).limit(5).collect(Collectors.toList());
        return top5Ids;
    }

    private void initScoreSplit(int skipNumber, int size) {
        Query query = new Query();
        query.with(Sort.by(Sort.Order.asc("video_id")));
        query.skip(skipNumber);
        query.limit(size);

        List<HeadRecommendVideo> headVideo = logMongoTemplate.find(query, HeadRecommendVideo.class, MongoConstants.head_recommend_video);
        headVideo.stream().forEach(e -> {
            Query videoHotQuery = new Query();
            videoHotQuery.addCriteria(Criteria.where("video_id").is(e.getVideo_id()));

            Update update = new Update();
            update.set("is_score", 1);
            update.set("score", e.getScore());
            log.debug("??????????????????>>{}", e.getVideo_id());
            logMongoTemplate.findAndModify(videoHotQuery, update, VideoHotspotVo.class, MongoConstants.video_hotspot);
        });
    }

    private void addOrUpdateHotspot(RecVideosVo recVideosVo, Double defaultWeight, Double defaultWeight1) {
        String videoId = recVideosVo.getId().toString();
        log.debug("????????????????????????>>{}", videoId);

        // ????????????????????????
        Double[] weights = this.getWeight(videoId, defaultWeight, defaultWeight1);
        log.debug("????????????????????????>>{}, ??????>>{}", videoId, weights);
        List<FullLable> top5Tag = this.getVideoPaddleTagTop5(Integer.parseInt(videoId));

        Query query = new Query(Criteria.where("video_id").is(videoId));
        logMongoTemplate.remove(query, MongoConstants.video_hotspot);
        logMongoTemplate.remove(query, MongoConstants.incentive_video_hotspot);
        log.debug("????????????????????????>>{}, ???????????????:{}, ?????????????????????", videoId, recVideosVo.getState());

        if (recVideosVo.getState() == 1 && recVideosVo.getUpdateType() == 0) {
            // ??????ID
            int album_id = ablumService.getVideoAblumId(videoId);
            Update update = this.getUpdate(recVideosVo, album_id, weights[0], weights[1], top5Tag);
            update.set("album_id", album_id);
            if (recVideosVo.getIncentiveVideo() == 1) {
                IncentiveVideoHotspot incentiveVideoHotspot = logMongoTemplate.findAndModify(query, update, IncentiveVideoHotspot.class, MongoConstants.incentive_video_hotspot);
                if (incentiveVideoHotspot == null) {
                    incentiveVideoHotspot = new IncentiveVideoHotspot(recVideosVo, album_id, weights[0], weights[1], top5Tag);
                    logMongoTemplate.save(incentiveVideoHotspot, MongoConstants.incentive_video_hotspot);
                    log.debug("????????????????????????>>{}, ?????????????????????", videoId);
                } else {
                    log.debug("????????????????????????>>{}, ?????????????????????", videoId);
                }
            } else {
                VideoHotspotVo videoHotspotVo = logMongoTemplate.findAndModify(query, update, VideoHotspotVo.class, MongoConstants.video_hotspot);
                if (videoHotspotVo == null) {
                    videoHotspotVo = new VideoHotspotVo(recVideosVo, album_id, weights[0], weights[1], top5Tag);
                    logMongoTemplate.save(videoHotspotVo, MongoConstants.video_hotspot);
                    log.debug("????????????????????????>>{}, ?????????????????????", videoId);
                } else {
                    log.debug("????????????????????????>>{}, ?????????????????????", videoId);
                }
            }
        }
    }

    private Update getUpdate(RecVideosVo recVideosVo, int albumId, Double weights, Double weights1, List<FullLable> top5Ids) {
        Update update = new Update();
        update.set("catid", recVideosVo.getCatId().intValue());
        update.set("state", recVideosVo.getState() == 1 ? 1 : 0);
        update.set("collection_id", recVideosVo.getGatherId().intValue());
        update.set("album_id", albumId);
        update.set("sensitive", recVideosVo.getSensitive());
        update.set("weights", weights);
        update.set("weights1", weights1);
        update.set("online_time", DateUtil.dateStr4(recVideosVo.getOnlineDate()));
        int videoTime = TimeUtil.changeMinuteStringToSecond(recVideosVo.getVideoTime());
        update.set("video_time", videoTime);
        //update.set("is_score", 0);
        //update.set("score", 0.0D);
        update.set("videos_source", recVideosVo.getVideosSource());
        update.set("video_url", 0);
        update.set("video_url", recVideosVo.getVideoUrl());
        update.set("top5_ids", top5Ids);
        update.set("update_at", new Date());
        return update;
    }

    /**
     * ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????
     *
     * @param videoId
     * @param defaultWeight
     * @return
     */
    private Double[] getWeight(String videoId, Double defaultWeight, Double defaultWeight1) {
        Double[] weight = new Double[]{defaultWeight, defaultWeight1};
        Query query = new Query(Criteria.where("video_id").is(videoId));
        query.with(Sort.by(Sort.Order.desc("update_at")));
        VideoHotspotVo videoHotspotVo = logMongoTemplate.findOne(query, VideoHotspotVo.class, MongoConstants.video_hotspot);
        IncentiveVideoHotspot incentiveVideoHotspot = logMongoTemplate.findOne(query, IncentiveVideoHotspot.class, MongoConstants.incentive_video_hotspot);
        if (incentiveVideoHotspot == null && videoHotspotVo == null) {
            return weight;
        }
        if (incentiveVideoHotspot != null && videoHotspotVo != null) {
            int cn = incentiveVideoHotspot.getUpdate_at().compareTo(videoHotspotVo.getUpdate_at());
            if (cn == -1) {
                weight[0] = videoHotspotVo.getWeights();
                weight[1] = videoHotspotVo.getWeights1();
            } else {
                weight[0] = incentiveVideoHotspot.getWeights();
                weight[1] = incentiveVideoHotspot.getWeights1();
            }
        } else if (incentiveVideoHotspot != null) {
            weight[0] = incentiveVideoHotspot.getWeights();
            weight[1] = incentiveVideoHotspot.getWeights1();
        } else if (videoHotspotVo != null) {
            weight[0] = videoHotspotVo.getWeights();
            weight[1] = videoHotspotVo.getWeights1();
        }
        return weight;
    }
}
