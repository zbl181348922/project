package com.miguan.recommend.service.recommend.impl;

import com.alibaba.fastjson.JSONObject;
import com.miguan.recommend.bo.BaseDto;
import com.miguan.recommend.bo.VideoRelavantRecommendDto;
import com.miguan.recommend.common.config.EsConfig;
import com.miguan.recommend.common.constants.ExistConstants;
import com.miguan.recommend.common.constants.MongoConstants;
import com.miguan.recommend.common.constants.RedisRecommendConstants;
import com.miguan.recommend.common.constants.SymbolConstants;
import com.miguan.recommend.common.es.EsDao;
import com.miguan.recommend.entity.es.HotspotVideos;
import com.miguan.recommend.entity.es.VideoEmbeddingEs;
import com.miguan.recommend.entity.mongo.FullLable;
import com.miguan.recommend.entity.mongo.VideoHotspotVo;
import com.miguan.recommend.service.BloomFilterService;
import com.miguan.recommend.service.RedisService;
import com.miguan.recommend.service.es.EsSearchService;
import com.miguan.recommend.service.recommend.EmbeddingService;
import com.miguan.recommend.service.recommend.VideoRecommendService;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.util.CollectionUtils.isEmpty;

@Slf4j
@Service("videoRelevantRecommendService")
public class VideoRelevantRecommendServiceImpl implements VideoRecommendService<VideoRelavantRecommendDto> {

    @Resource(name = "logMongoTemplate")
    private MongoTemplate logMongoTemplate;
    @Resource(name = "redisDB0Service")
    private RedisService redisDB0Service;
    @Resource
    private EsConfig esConfig;
    @Resource
    private EsDao esDao;
    @Resource
    private EmbeddingService embeddingService;
    @Resource
    private EsSearchService esSearchService;
    @Resource
    private BloomFilterService bloomFilterService;

    @Override
    public void recommend(BaseDto baseDto, VideoRelavantRecommendDto recommendDto) {
        List<String> relevantVideos = null;
        long t1 = System.currentTimeMillis();
        switch (baseDto.getRelevantGroup()) {
            case "2":
                // ????????????????????????
                relevantVideos = this.recommendByVideoEmbedding(baseDto, recommendDto);
                log.info("{} ?????????????????? ??????????????????ID???{}", baseDto.getUuid(), JSONObject.toJSONString(relevantVideos));
                break;
            case "3":
                // ??????????????????????????????
                relevantVideos = this.recommendByPaddleTag(baseDto, recommendDto);
                log.info("{} ?????????????????? ??????????????????ID???{}", baseDto.getUuid(), JSONObject.toJSONString(relevantVideos));
                break;
            default:
                // ????????????????????????
                relevantVideos = this.recommendByVideoTitle(baseDto, recommendDto);
                log.info("{} ?????????????????? ??????????????????ID???{}", baseDto.getUuid(), JSONObject.toJSONString(relevantVideos));
        }
        log.info("{} ?????????????????? ????????????{}", baseDto.getUuid(), System.currentTimeMillis() - t1);
        recommendDto.setRecommendVideo(relevantVideos);
        bloomFilterService.putAll(baseDto.getUuid(), relevantVideos);
    }

    private List<String> recommendByVideoTitle(BaseDto baseDto, VideoRelavantRecommendDto recommendDto) {

        List<String> relevantVideos = null;
        String videoTitle = this.getVideoTitle(recommendDto.getVideoId());
        if (StringUtils.isEmpty(videoTitle)) {
            log.info("{} ?????????????????? ?????????{}?????????????????????", baseDto.getUuid(), recommendDto.getVideoId());
        } else {
            relevantVideos = esSearchService.relevantVideoOfTitle(videoTitle);
            if (isEmpty(relevantVideos)) {
                log.info("{} ?????????????????? ?????????{}???????????????????????????0???", baseDto.getUuid(), recommendDto.getVideoId());
            } else {
                relevantVideos.remove(recommendDto.getVideoId());
                log.info("{} ?????????????????? ?????????{}???????????????????????????{}???", baseDto.getUuid(), recommendDto.getVideoId(), relevantVideos.size());
                relevantVideos = bloomFilterService.containMuilSplit(recommendDto.getNum(), baseDto.getUuid(), relevantVideos);
            }
        }
        return relevantVideos;
    }

    private String getVideoTitle(String videoId){
        String key = RedisRecommendConstants.video_title + videoId;
        String title = redisDB0Service.get(key);
        if(StringUtils.isEmpty(title)){
            SearchSourceBuilder idBuilder = new SearchSourceBuilder();
            idBuilder.query(new TermQueryBuilder("id", videoId));
            List<HotspotVideos> videosList = esDao.search(esConfig.getVideo_title(), idBuilder, HotspotVideos.class);
            if (!isEmpty(videosList)) {
                title = videosList.get(0).getTitle();
                redisDB0Service.set(key, title, ExistConstants.one_hour_seconds);
            }
        }
        return title;
    }

    /**
     * ????????????????????????
     *
     * @param baseDto
     * @param recommendDto
     * @return
     */
    private List<String> recommendByVideoEmbedding(BaseDto baseDto, VideoRelavantRecommendDto recommendDto) {
        String vector = this.getVideoEmbeddingVector(baseDto.getUuid(), recommendDto.getVideoId());
        log.info("{} ?????????????????? ?????????{}????????????{}", baseDto.getUuid(), recommendDto.getVideoId(), vector);
        // ????????????????????????????????????
        List<String> relevantVideos = embeddingService.findVideoByVideoEmbeddingVector(vector);
        if(isEmpty(relevantVideos)){
            log.info("{} ?????????????????? ?????????{}?????????????????????0?????????", baseDto.getUuid(), recommendDto.getVideoId());
            return null;
        }
        relevantVideos.remove(recommendDto.getVideoId());
        log.info("{} ?????????????????? ?????????{}?????????????????????{}?????????", baseDto.getUuid(), recommendDto.getVideoId(), relevantVideos.size());
        if(isEmpty(relevantVideos)){
            return null;
        }
        return bloomFilterService.containMuilSplit(recommendDto.getNum(), baseDto.getUuid(), relevantVideos);
    }

    /**
     * ??????????????????
     *
     * @param videoId ??????ID
     * @return
     */
    public String getVideoEmbeddingVector(String uuid, String videoId) {
        String key = RedisRecommendConstants.video_embedding_vector + videoId;
        String value = redisDB0Service.get(key);
        if (StringUtils.isEmpty(value)) {
            Query query = new Query();
            query.addCriteria(Criteria.where("video_id").is(videoId));
            List<VideoHotspotVo> videoVo = logMongoTemplate.find(query, VideoHotspotVo.class, MongoConstants.video_hotspot);
            if(CollectionUtils.isEmpty(videoVo)){
                return null;
            }
            String videoUrl = videoVo.get(0).getVideo_url();
            if(StringUtils.isEmpty(videoUrl)){
                log.error("{} ?????????????????? ?????????{}???????????????", uuid, videoId);
                return null;
            }
            // ??????????????????
            VideoEmbeddingEs embeddingEs = embeddingService.getVideoEmbeddingVector(videoId, videoUrl);
            if (embeddingEs == null || StringUtils.isEmpty(embeddingEs.getVector())) {
                return null;
            }
            value = embeddingEs.getVector();
            redisDB0Service.set(key, value, ExistConstants.five_minutes_seconds);
        }
        return value;
    }

    /**
     * ??????????????????????????????
     *
     * @param baseDto
     * @param recommendDto
     * @return
     */
    private List<String> recommendByPaddleTag(BaseDto baseDto, VideoRelavantRecommendDto recommendDto) {
        // ????????????????????????3?????????ID
        List<Integer> top3Ids = this.findVideoTop3Ids(recommendDto.getVideoId());
        if(isEmpty(top3Ids)){
            log.info("{} ?????????????????? ?????????{}, ????????????????????????????????????", baseDto.getUuid(), recommendDto.getVideoId());
            return null;
        }
        log.info("{} ?????????????????? ?????????{}, ??????ID???{}", baseDto.getUuid(), recommendDto.getVideoId(), JSONObject.toJSONString(top3Ids));
        List<String> relevantVideos = this.findRelevantVideoInTop5Ids(top3Ids, recommendDto.getSensitiveState());
        if (isEmpty(relevantVideos)) {
            log.info("{} ?????????????????? ?????????{}, ????????????????????????0???", baseDto.getUuid(), recommendDto.getVideoId());
            return null;
        }
        relevantVideos.remove(recommendDto.getVideoId());
        log.info("{} ?????????????????? ?????????{}, ????????????????????????{}???", baseDto.getUuid(), recommendDto.getVideoId(), relevantVideos.size());
        if(isEmpty(relevantVideos)){
            return null;
        }
        return bloomFilterService.containMuilSplit(recommendDto.getNum(), baseDto.getUuid(), relevantVideos);
    }

    /**
     * ?????????????????????
     *
     * @param videoHotspotVo ??????????????????
     * @param tagIds         ?????????????????????ID
     * @return
     */
    private Double sumTagProbability(VideoHotspotVo videoHotspotVo, List<Integer> tagIds) {
        double sumProbability = 0.0D;
        List<FullLable> videoFullTag = videoHotspotVo.getTop5_ids();
        Map<Integer, Double> tagProbabilityMap = videoFullTag.stream().collect(Collectors.toMap(FullLable::getClass_id, FullLable::getProbability));
        for (Integer tagId : tagIds) {
            double tagProbability = tagProbabilityMap.get(tagId);
            sumProbability += tagProbability;
        }
        return sumProbability;
    }

    /**
     * ??????????????????5?????????,??????????????????ID?????????
     *
     * @param top5Ids   ????????????5?????????
     * @param sensitive 0 ????????????1 ???????????????
     * @return
     */
    private List<String> findRelevantVideoInTop5Ids(List<Integer> top5Ids, Integer sensitive) {

        List<String> relevantVideos = null;

        String key = RedisRecommendConstants.relevant_video_of_tag3_ids + StringUtils.collectionToDelimitedString(top5Ids, SymbolConstants.comma);
        String value = redisDB0Service.get(key);
        if (StringUtils.isEmpty(value)) {
            // ?????????????????????5?????????????????????????????????3???????????????
            Query relevantQuery = new Query();
            relevantQuery.addCriteria(Criteria.where("state").is(1));
            relevantQuery.addCriteria(Criteria.where("top5_ids.'class_id").all(top5Ids));
            if (sensitive != null && sensitive == 1) {
                relevantQuery.addCriteria(Criteria.where("sensitive").is(-2));

            }
            log.debug("????????????[??????]??????????????????????????????{}", relevantQuery.toString());
            List<VideoHotspotVo> relevantList = logMongoTemplate.find(relevantQuery, VideoHotspotVo.class, MongoConstants.video_hotspot);
            if (isEmpty(relevantList)) {
                log.debug("????????????[??????]??????????????????{}?????????????????????????????????", JSONObject.toJSONString(top5Ids));
                return null;
            }

            // ????????????????????????????????????????????????
            relevantVideos = relevantList.stream().sorted((v1, v2) -> {
                return this.sumTagProbability(v2, top5Ids).compareTo(this.sumTagProbability(v1, top5Ids));
            }).map(VideoHotspotVo::getVideo_id).collect(Collectors.toList());

            value = StringUtils.collectionToDelimitedString(relevantVideos, SymbolConstants.comma);
            redisDB0Service.set(key, value, ExistConstants.one_hour_seconds);

        } else {
            relevantVideos = Stream.of(value.split(SymbolConstants.comma)).collect(Collectors.toList());
        }
        log.debug("????????????[??????]??????????????????????????????{}", JSONObject.toJSONString(relevantVideos));
        return relevantVideos;
    }

    /**
     * ????????????????????????3?????????ID
     *
     * @param videoId ??????ID
     * @return
     */
    private List<Integer> findVideoTop3Ids(String videoId) {
        String key = RedisRecommendConstants.video_paddle_tag3_ids + videoId;
        String value = redisDB0Service.get(key);
        List<Integer> top3Ids = null;
        if (StringUtils.isEmpty(value)) {
            // ??????????????????ID???????????????????????????3?????????
            Query query = new Query();
            query.addCriteria(Criteria.where("video_id").is(videoId));
            VideoHotspotVo videoHotspotVo = logMongoTemplate.findOne(query, VideoHotspotVo.class, MongoConstants.video_hotspot);
            if (videoHotspotVo == null || isEmpty(videoHotspotVo.getTop5_ids())) {
                log.debug("????????????[??????]?????????????????????[{}]???????????????????????????", videoId);
                return null;
            }
            top3Ids = videoHotspotVo.getTop5_ids().stream().limit(3L).map(FullLable::getClass_id).sorted().collect(Collectors.toList());
            value = StringUtils.collectionToDelimitedString(top3Ids, SymbolConstants.comma);
            redisDB0Service.set(key, value, ExistConstants.one_hour_seconds);
        } else {
            top3Ids = Stream.of(value.split(SymbolConstants.comma)).map(Integer::new).collect(Collectors.toList());
        }
        log.debug("{????????????[??????]??????????????????[{}]????????????{}", videoId, JSONObject.toJSONString(top3Ids));
        return top3Ids;
    }

}
