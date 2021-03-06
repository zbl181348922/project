package com.miguan.ballvideo.service.recommend;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.miguan.ballvideo.common.constants.Constant;
import com.miguan.ballvideo.common.constants.RecommendConstant;
import com.miguan.ballvideo.common.interceptor.argument.params.AbTestAdvParamsVo;
import com.miguan.ballvideo.common.util.ChannelUtil;
import com.miguan.ballvideo.common.util.PackageUtil;
import com.miguan.ballvideo.common.util.VersionUtil;
import com.miguan.ballvideo.common.util.adv.AdvUtils;
import com.miguan.ballvideo.common.util.recommend.FeatureUtil;
import com.miguan.ballvideo.common.util.video.VideoUtils;
import com.miguan.ballvideo.dto.VideoParamsDto;
import com.miguan.ballvideo.entity.MarketAudit;
import com.miguan.ballvideo.entity.recommend.UserFeature;
import com.miguan.ballvideo.mapper.VideosCatMapper;
import com.miguan.ballvideo.redis.util.RedisKeyConstant;
import com.miguan.ballvideo.service.*;
import com.miguan.ballvideo.vo.AdvertCodeVo;
import com.miguan.ballvideo.vo.ClUserVideoInfoVo;
import com.miguan.ballvideo.vo.mongodb.IncentiveVideoHotspot;
import com.miguan.ballvideo.vo.mongodb.VideoHotspotVo;
import com.miguan.ballvideo.vo.video.FirstVideos161Vo;
import com.miguan.ballvideo.vo.video.FirstVideosVo;
import com.miguan.ballvideo.vo.video.VideoGatherVo;
import com.miguan.ballvideo.vo.video.Videos161Vo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.collections.CollectionUtils.isEmpty;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * ??????0.3????????????????????????2????????????????????????spark?????????????????????
 * ????????????
 */
@Service
@Slf4j
public class RecommendVideosServiceImpl3Asyn {

    @Resource
    private VideoCacheService videoCacheService;
    @Resource
    private ClUserService clUserService;
    @Resource
    private AdvertService advertService;
    @Resource
    private VideoGatherService videoGatherService;
    @Resource
    private VideosCatMapper videosCatMapper;
    @Resource
    private MarketAuditService marketAuditService;
    @Autowired
    private FindRecommendVideosServiceImpl findRecommendVideosService;
    @Autowired
    private FindRecommendPoolVideosServiceImpl findRecommendPoolVideosService;
    @Autowired
    private FindRecommendJLVideosServiceImpl findRecommendJLVideosService;
    @Autowired
    private FindRecommendCatidServiceImpl findRecommendCatidService;
    @Autowired
    private FindRecommendEsServiceImpl findRecommendEsService;
    @Autowired
    private ClUserVideosService clUserVideosService;
    @Resource
    private RedisService redisService;
    @Autowired
    private FeatureUtil featureUtil;
    @Autowired
    private PredictServiceImpl predictService;
    @Resource(name = "recDB9Pool")
    private JedisPool jedisPool;
    @Autowired
    private BloomFilterService bloomFilterService;

    Function<List<Integer>, Integer> function = catids -> {
        int rn = RandomUtils.nextInt(1, 7);
        if (rn < 4) {
            return catids.get(0);
        } else if (rn < 6) {
            return catids.size() < 2 ? catids.get(catids.size() - 1) : catids.get(1);
        }
        return catids.size() < 3 ? catids.get(catids.size() - 1) : catids.get(2);
    };
    ExecutorService executor = new ThreadPoolExecutor(32, 200, 10L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(5000));

    /**
     * ??????
     *
     * @param params
     * @return
     */
    public FirstVideos161Vo getRecommendVideos(VideoParamsDto params, AbTestAdvParamsVo queueVo) {
        String uuid = params.getUuid();
        if (StringUtils.isBlank(uuid)) {
            throw new NullPointerException("????????????uuid");
        }
        long pt = System.currentTimeMillis();
        FirstVideos161Vo vo = getVideos(uuid, params, queueVo);
        log.warn("{} ??????0.3????????????{}", uuid, (System.currentTimeMillis() - pt));
        return vo;
    }

    private FirstVideos161Vo getVideos(String uuid, VideoParamsDto params, AbTestAdvParamsVo queueVo) {
        //???????????????????????????????????????
        MarketAudit marketAudit = marketAuditService.getCatIdsByChannelIdAndAppVersion(params.getChannelId(), params.getAppVersion());
        //?????????????????????
        List<String> catIds = videosCatMapper.queryCatIdsList(params.getAppPackage());
        if (catIds == null) {
            catIds = Lists.newArrayList();
        }
        List<Integer> excludeCatid = null;
        List<Integer> excludeCollectid = null;
        if (marketAudit != null && isNotBlank(marketAudit.getCatIds())) {
            String[] excludeCatidStrArray = marketAudit.getCatIds().split(",");
            CollectionUtils.addAll(catIds, excludeCatidStrArray);
        }
        if (marketAudit != null && isNotBlank(marketAudit.getGatherIds())) {
            String[] excludeCollectidArray = marketAudit.getGatherIds().split(",");
            excludeCollectid = Stream.of(excludeCollectidArray).map(Integer::valueOf).collect(Collectors.toList());
        }
        if (!catIds.isEmpty()) {
            excludeCatid = catIds.stream().filter(e -> e != null).map(Integer::valueOf).collect(Collectors.toList());
        }
        //????????????3??????????????????3?????????6?????????????????????
        List<Integer> userCatids = findRecommendCatidService.getUserCatids(uuid, params.getChannelId());
        int firstCatid = userCatids.get(0);
        //???????????????????????????
        if (!isEmpty(excludeCatid)) {
            userCatids.removeAll(excludeCatid);
        }
        //??????1????????????????????????????????????????????????(????????????)6???????????????????????????
        List<Integer> hotspotCatids = findRecommendCatidService.getHotspotCatids(firstCatid, excludeCatid);
        int diffCount = 3 - userCatids.size();
        //??????????????????,??????3?????????6?????????????????????
        if (diffCount > 0 && !isEmpty(hotspotCatids)) {
            if (hotspotCatids.size() < diffCount) {
                diffCount = hotspotCatids.size();
            }
            for (int i = 0; i < diffCount; i++) {
                userCatids.add(hotspotCatids.get(i));
            }
        }
        //?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        hotspotCatids = ListUtils.subtract(hotspotCatids, userCatids);
        if (isEmpty(userCatids)) {
            userCatids.add(firstCatid);
        }
        if (isEmpty(hotspotCatids)) {
            hotspotCatids.add(firstCatid);
        }
        List<IncentiveVideoHotspot> jlvideos = new ArrayList<>();

        //????????????????????????
        Map<String, Object> incentiveMap = AdvUtils.getIncentiveInfo(params.getMobileType(), params.getAppPackage(), queueVo,1);
        Integer isShowIncentive = incentiveMap.get("isShowIncentive")==null? 0 : Integer.valueOf(incentiveMap.get("isShowIncentive").toString());
        Integer position = incentiveMap.get("position")==null? 0 : Integer.valueOf(incentiveMap.get("position").toString());
        String incentiveVideoRate = incentiveMap.get("incentiveVideoRate")==null? "0" : incentiveMap.get("incentiveVideoRate").toString();

        long pt = System.currentTimeMillis();
        //??????????????????
        boolean isNewUUID = redisService.exits(RedisKeyConstant.UUID_KEY + uuid);
        List<String> searchVideo = getRecommendVideo(params, hotspotCatids, userCatids, excludeCatid, excludeCollectid, jlvideos, isNewUUID, params.isABTest());
        log.warn("{} ??????0.3?????????{}", uuid, (System.currentTimeMillis() - pt));
        //????????????id??????es
        List<Videos161Vo> rvideos = findRecommendEsService.list(searchVideo, incentiveVideoRate);
        List<Videos161Vo> firstVideos = new ArrayList<>();
        if (isNotEmpty(rvideos)) {
            //lzhong ?????????
            // ???????????????????????????
            List<Videos161Vo> notjl = rvideos.stream().filter(e -> e.getIncentiveVideo() == null || e.getIncentiveVideo().intValue() != 1).collect(Collectors.toList());
            firstVideos.addAll(notjl);

            List<Videos161Vo> jl = rvideos.stream().filter(e -> e.getIncentiveVideo() != null && e.getIncentiveVideo().intValue() == 1).collect(Collectors.toList());
            // ????????????????????????????????????????????????
            if (isNewUUID) {
                firstVideos.addAll(jl);
            } else {
                //????????????????????????,??????????????????????????????????????????
                position = position > searchVideo.size() ? searchVideo.size() -1 : position <= 0 ? 0 : (position - 1);
                firstVideos.addAll(position, jl);
            }
            jl.clear();
        }
        //?????????????????????????????????, ??????????????????ID
        if (isNotEmpty(firstVideos)) {


            fillLoginInfo(params, firstVideos);
            //????????????2.5???????????????????????????????????????
            if (VersionUtil.compareIsHigh(Constant.APPVERSION_250, params.getAppVersion())) {
                //2.1.0????????????????????????
                fillGatherVideos(firstVideos);
            }
            videoCacheService.fillParams(firstVideos);
            //???????????????????????????????????????????????????????????????????????????
            clUserService.packagingUserAndVideos(firstVideos);
        }

        FirstVideos161Vo firstVideosNewVo = new FirstVideos161Vo();
        //??????????????????
        if (VersionUtil.isBetween(Constant.APPVERSION_253, Constant.APPVERSION_257, params.getAppVersion()) && Constant.ANDROID.equals(params.getMobileType())) {
            List<FirstVideosVo> firstVideosVos = VideoUtils.getFirstVideosVos(firstVideos);
            firstVideosNewVo.setFirstVideosVos(firstVideosVos);
            firstVideos.clear();
        } else {
            //??????2?????????
            String channelId = params.getChannelId();
            params.setChannelId(ChannelUtil.filter(channelId));
            Map<String, Object> adverMap = new HashMap<>();
            String appVersion = params.getAppVersion();
            adverMap.put("marketChannelId", params.getChannelId());
            adverMap.put("channelId", params.getChannelId());
            adverMap.put("appVersion", appVersion);
            adverMap.put("positionType", params.getPositionType());
            adverMap.put("mobileType", params.getMobileType());
            adverMap.put("permission", params.getPermission());
            adverMap.put("appPackage", PackageUtil.getAppPackage(params.getAppPackage(), params.getMobileType()));
            //V2.5.0??????????????????
            packagNewAdvertAndVideos(firstVideosNewVo, firstVideos, adverMap, queueVo);
        }
        //???????????????????????????
        firstVideosNewVo.setVideoDuty("4,3,1");
        findRecommendVideosService.recordHistory(uuid, searchVideo);
        findRecommendJLVideosService.recordHistory(uuid, jlvideos);
        return firstVideosNewVo;
    }

    @SuppressWarnings("unchecked")
    public List<String> getRecommendVideo(VideoParamsDto inputParams,
                                 List<Integer> hotspotCatids, List<Integer> userCatids,
                                 List<Integer> excludeCatid, List<Integer> excludeCollectid,
                                 List<IncentiveVideoHotspot> jlvideos,
                                 boolean isNewUUID, boolean isABTest) {
        String uuid = inputParams.getUuid();
        List<String> recVideoList = new ArrayList<>();

        List<Object> videoInfoForRecList;
        videoInfoForRecList = getVideoFromCache(inputParams,hotspotCatids,userCatids,excludeCatid,excludeCollectid,jlvideos,isNewUUID,isABTest);

        if(videoInfoForRecList == null || videoInfoForRecList.isEmpty()){
            return recVideoList;
        }

        Map<String,BigDecimal> videoPlayRateMap = (Map<String,BigDecimal>)videoInfoForRecList.get(0);
        Map<String,Integer> videoCatMap = (Map<String,Integer>)videoInfoForRecList.get(1);

        int needCount = 7;
        int oneCatLimit = 2;
        int limitMulti = 20;
        if(inputParams.getUserFeature().getPublicInfo().isNew()){
            oneCatLimit = 3;
            limitMulti = 2;
        }

        recVideoList= videoTopK(videoPlayRateMap,videoCatMap,needCount,oneCatLimit, limitMulti);
        recVideoList.addAll(jlvideos.stream().map(IncentiveVideoHotspot::getVideo_id).collect(Collectors.toList()));
        log.warn("??????0.3?????????uuid({})????????? {} ?????????????????????????????? {} ???, ????????????ID??????{}", uuid, recVideoList.size(), jlvideos.size(), JSON.toJSONString(recVideoList));
        return recVideoList;
    }

    @SuppressWarnings("unchecked")
    public List<Object> getVideoFromCache(VideoParamsDto inputParams,
                                          List<Integer> hotspotCatids, List<Integer> userCatids,
                                          List<Integer> excludeCatid, List<Integer> excludeCollectid,
                                          List<IncentiveVideoHotspot> jlvideos,
                                          boolean isNewUUID, boolean isABTest){
        List<Object> videoInfoForRecList = null;
        boolean needFilter = false;
        int cacheExpireTime = isNewUUID? 30 : 60;
        // ??????????????????
        try (Jedis con = jedisPool.getResource()) {
            String key = String.format(RecommendConstant.key_user_rec_video_list, inputParams.getUuid());
            String cacheList = con.get(key);
            if(cacheList== null || cacheList.isEmpty()){
                videoInfoForRecList = getAsync(inputParams,hotspotCatids,userCatids,excludeCatid,excludeCollectid,jlvideos,isNewUUID,isABTest);
                String cacheValue = JSON.toJSONString(videoInfoForRecList);
                con.setex(key,cacheExpireTime,cacheValue);
            }else{
                videoInfoForRecList = (List<Object>)JSON.parse(cacheList);
                needFilter = true;
            }
            Map<String,BigDecimal> videoPlayRateMap = (Map<String,BigDecimal>)videoInfoForRecList.get(0);
            Map<String,Integer> videoCatMap = (Map<String,Integer>)videoInfoForRecList.get(1);
            if(needFilter){
                videoPlayRateMap = filterSortedMap(videoPlayRateMap, inputParams.getUuid());
                CompletableFuture<Integer> f4 = rule4Video(inputParams.getUuid(), hotspotCatids, excludeCatid, excludeCollectid, jlvideos, inputParams.isABTest());
                f4.join();
            }
            return Arrays.asList(videoPlayRateMap,videoCatMap);
        }
    }

    public Map<String,BigDecimal> filterSortedMap(Map<String,BigDecimal> sortedVideoMap, String uuid){
        List<String> vidList = Lists.newArrayList(sortedVideoMap.keySet());
        List<String> filteredVidList = bloomFilterService.containMuil(vidList.size(),uuid,vidList);
        Map<String,BigDecimal> filteredMap = new LinkedHashMap<>();
        filteredVidList.stream().forEach(vid ->filteredMap.put(vid,sortedVideoMap.get(vid)));
        return filteredMap;
    }

    public List<Object> getAsync(VideoParamsDto inputParams,
                                 List<Integer> hotspotCatids, List<Integer> userCatids,
                                 List<Integer> excludeCatid, List<Integer> excludeCollectid,
                                 List<IncentiveVideoHotspot> jlvideos,
                                 boolean isNewUUID, boolean isABTest) {
        UserFeature userFeature = inputParams.getUserFeature();
        String uuid = userFeature.getPublicInfo().getUuid();
        List<Integer> jlHotspotCatids = Lists.newArrayList(hotspotCatids);
        List<VideoHotspotVo> cfRecomendVideos = Lists.newArrayList();
        Map<Integer, List<VideoHotspotVo>> catVideoMap = Maps.newHashMapWithExpectedSize(400);
        if(CollectionUtils.isNotEmpty(hotspotCatids)){
            int hotspotCatidSize = hotspotCatids.size();
            userCatids.addAll(hotspotCatids.subList(0, hotspotCatidSize> 3 ? 3 : hotspotCatidSize));
        }
        CompletableFuture<Void> fCatVideos = rule1Video(uuid, userCatids, excludeCollectid, catVideoMap, inputParams.isABTest());
        CompletableFuture fOfflineCf = rule2Video(uuid, excludeCatid, cfRecomendVideos, isABTest);
        CompletableFuture<Integer> f4 = rule4Video(uuid, jlHotspotCatids, excludeCatid, excludeCollectid, jlvideos, inputParams.isABTest());
        CompletableFuture<Void> f6 = CompletableFuture.allOf(fOfflineCf, f4, fCatVideos);
        long pt = System.currentTimeMillis();
        f6.join();

        List<String> videoList = new ArrayList<>();
        Map<String,Integer> videoCatMap = new LinkedHashMap<>();
        Map<String,VideoHotspotVo> voMap = new LinkedHashMap<>();
        for(VideoHotspotVo vo:cfRecomendVideos){
            videoList.add(vo.getVideo_id());
            videoCatMap.put(vo.getVideo_id(),vo.getCatid());
            voMap.put(vo.getVideo_id(),vo);
        }
        int catSum = 0;
        for(Integer catId: catVideoMap.keySet()){
            List<VideoHotspotVo> catVideoList =catVideoMap.get(catId);
            for(VideoHotspotVo vo:catVideoList){
                videoList.add(vo.getVideo_id());
                videoCatMap.put(vo.getVideo_id(),catId);
                voMap.put(vo.getVideo_id(),vo);
            }
            catSum+=catVideoList.size();
        }
        List<String> uniqVideoList = videoList.stream().distinct().collect(Collectors.toList());

        long pt2 = System.currentTimeMillis();
        log.warn("{} ??????0.3??????????????????{}??????????????????{}????????????????????????{}????????????????????????{}", uuid, (pt2- pt), uniqVideoList.size(),catSum,cfRecomendVideos.size());

        // ????????????????????????
        int needCount = 7;
        Map<String,BigDecimal>  sortedVideoMap = getVideoListPlayRate(uniqVideoList,userFeature,videoCatMap, needCount,inputParams.isABTest(), voMap);
        long pt3 = System.currentTimeMillis();
        log.warn("??????0.3???????????????????????????"+(pt3-pt2));

        return Arrays.asList(sortedVideoMap,videoCatMap);
    }

    public Map<String,BigDecimal> sortVideoMap(Map<String,BigDecimal> videoPlayRateMap,int needCount, int limitMulti){
        Map<String,BigDecimal> sortedMap = new LinkedHashMap<>();
        videoPlayRateMap.entrySet().stream()
                .sorted((p1,p2)-> p2.getValue().compareTo(p1.getValue()))
                .limit(needCount * limitMulti)
                .collect(Collectors.toList())
                .forEach(ele -> sortedMap.put(ele.getKey(), ele.getValue()));
        return sortedMap;
    }

    /**
     * ??????????????????????????????
     */
    public Map<String,BigDecimal> getVideoListPlayRate(List<String> videoList, UserFeature userFeature, Map<String,Integer> videoCatMap, int needCount, boolean isABTest, Map<String,VideoHotspotVo> voMap){
        // ???????????????????????????????????????????????????????
        long pt1 = System.currentTimeMillis();
        List<Map<String,Object>> listFeature = featureUtil.makeFeatureList(videoList,userFeature,videoCatMap,isABTest);
        long pt2 = System.currentTimeMillis();
        log.warn("??????0.3????????????????????????"+(pt2-pt1));
        Map<String,BigDecimal> videoPlayRateMap = predictService.predictPlayRate(listFeature, isABTest);
        long pt3 = System.currentTimeMillis();
        log.warn("??????0.3???????????????????????????"+(pt3-pt2));
        if(videoPlayRateMap == null && videoPlayRateMap.size() != listFeature.size()){
            return null;
        }

        if(isABTest){
//            caculateScore(videoList, videoPlayRateMap, voMap, userFeature);
        }

        return videoPlayRateMap;
    }

    private final Map<Integer,Double> videoTimeScoreMap = new LinkedHashMap<Integer,Double>(){
        {
            put(3,2D);
            put(4,1.5);
            put(2,1D);
            put(5,0.9);
            put(6,0.8);
        }
    };
    private void caculateScore(List<String> videoList, Map<String,BigDecimal> videoPlayRateMap, Map<String,VideoHotspotVo> voMap,UserFeature userFeature){
        Map<String,BigDecimal> scoreMap = new LinkedHashMap<>();
        // ?????????????????????????????????
        Map<String,Long> perPlayTimeMap =featureUtil.getVideoListPerPlayTime(videoList, userFeature);
        int i = 0;
        for(String vid:videoPlayRateMap.keySet()){
//            VideoHotspotVo vo = voMap.get(vid);
//            Integer second= vo.getVideo_time();
//            double scoreMulti = second>=90 && second<=480 ? 2 : 1;
            double scoreMulti = perPlayTimeMap.get(vid);
            BigDecimal score = videoPlayRateMap.get(vid).multiply(new BigDecimal(scoreMulti)).setScale(4, BigDecimal.ROUND_HALF_UP);
            videoPlayRateMap.put(vid, score);
            if(i++ < 1){
                log.warn("??????0.3?????????????????????"+videoPlayRateMap.get(vid)+"???pp second???"+scoreMulti+",score:"+score);
            }
        }
    }

    private List<String> videoTopK(Map<String,BigDecimal> videoPlayRateMap,Map<String,Integer> videoCatMap,int needCount, int oneCatLimit, int limitMulti){
        Map<String,BigDecimal> sortedMap = sortVideoMap(videoPlayRateMap,needCount,limitMulti);
        if(sortedMap.size() <= needCount){
            return new ArrayList<>(sortedMap.keySet());
        }

        // ????????????
        String printLine = "";
        List<String> recVideoList = new ArrayList<>();
        Map<Integer,Integer> catVideoCount = new LinkedHashMap<>();
        for(String vid:sortedMap.keySet()){
            int tmpCatId = videoCatMap.get(vid);
            int catCountInList = MapUtils.getIntValue(catVideoCount,tmpCatId,0);
            if(catCountInList >= oneCatLimit){
                continue;
            }
            recVideoList.add(vid);
            catVideoCount.put(tmpCatId, catCountInList+1);
            if(recVideoList.size() >= needCount){
                break;
            }
            printLine += ","+vid+":"+sortedMap.get(vid);
        }
        log.warn("???????????????????????????"+printLine);
        if(recVideoList.size() < needCount){
            // ????????????
            for(String vid:sortedMap.keySet()){
                if(recVideoList.contains(vid)){
                    continue;
                }
                recVideoList.add(vid);
                if(recVideoList.size() >= needCount){
                    break;
                }
            }
        }

        return recVideoList;
    }

    /**
     * ??????1??????????????????
     *
     * @param uuid
     * @param userCatids
     * @param excludeCollectid
     * @param searchVideoMap
     * @return
     */
    private CompletableFuture<Void> rule1Video(String uuid, List<Integer> userCatids, List<Integer> excludeCollectid, Map<Integer, List<VideoHotspotVo>> searchVideoMap, boolean isABTest) {
        //?????????????????????????????????????????????????????? key = catid, value = ??????
        Map<Integer, Long> catidDivide = new HashMap<>();
        long catFindCount = 90;
        for(Integer catId:userCatids){
            if(catFindCount <= 20) {
                catFindCount = 20;
            }
            catidDivide.put(catId,catFindCount);
            catFindCount -= 20;
        }
        Function<Integer[], Number> f = e -> {
            long pt = System.currentTimeMillis();
            //lzhong ?????? catidDivide ?????????1?????????6?????????
            List<VideoHotspotVo> videoId1 = findRecommendVideosService.getVideoInfo(uuid, e[0], e[1].intValue(), null,excludeCollectid, isABTest);
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3??????1??????????????????{}", uuid, isEmpty(videoId1) ? null : JSON.toJSONString(videoId1));
            }
            if (!isEmpty(videoId1)) {
                searchVideoMap.put(e[0], videoId1);
            }
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3??????1?????????{}", uuid, (System.currentTimeMillis() - pt));
            }
            return System.currentTimeMillis() - pt;
        };
        CompletableFuture[] listFeture = catidDivide.entrySet().stream().map(e -> {
            Integer[] params = new Integer[]{e.getKey(), e.getValue().intValue()};
            return CompletableFuture.completedFuture(params).thenApplyAsync(f, executor);
        }).toArray(size -> new CompletableFuture[size]);
        return CompletableFuture.allOf(listFeture);
    }

    /**
     * ?????????????????????????????????
     *
     * @return
     */
    private CompletableFuture<Integer> rule2Video(String uuid, List<Integer> catIds, List<VideoHotspotVo> searchPoolVideo, boolean isABTest) {
        return CompletableFuture.supplyAsync(() -> {
            //lzhong ???????????????????????????2?????????1?????????
            long pt = System.currentTimeMillis();
            List<VideoHotspotVo> videoId2 = findRecommendPoolVideosService.getVideoInfo(uuid, 100, catIds, isABTest);
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3??????2??????????????????{}", uuid, isEmpty(videoId2) ? null : JSON.toJSONString(videoId2.subList(0,10)));
            }
            if (!isEmpty(videoId2)) {
                searchPoolVideo.addAll(videoId2);
            }
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3??????2?????????{}", uuid, (System.currentTimeMillis() - pt));
            }
            return searchPoolVideo.size();
        }, executor);
    }

    /**
     * ??????4??????????????????????????????
     *
     * @param uuid
     * @param jlHotspotCatids
     * @param excludeCatid
     * @param excludeCollectid
     * @param jlvideos
     * @return
     */
    private CompletableFuture<Integer> rule4Video(String uuid, List<Integer> jlHotspotCatids, List<Integer> excludeCatid, List<Integer> excludeCollectid,
                                                  List<IncentiveVideoHotspot> jlvideos, boolean isABTest) {
        return CompletableFuture.supplyAsync(() -> {
            //lzhong ???????????????????????????4?????????1?????????(????????????)
            long pt = System.currentTimeMillis();
            int jlCatid = function.apply(jlHotspotCatids);
            List<IncentiveVideoHotspot> jlvideo = findRecommendJLVideosService.getVideoId(uuid, jlCatid, 1, excludeCatid, excludeCollectid, isABTest);
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3??????4????????????????????????{}", uuid, isEmpty(jlvideo) ? null : JSON.toJSONString(jlvideo));
            }
            if (isEmpty(jlvideo)) {
                log.warn("??????0.3??????4????????? uuid={} ?????????????????????", uuid);
            } else {
                jlvideos.addAll(jlvideo);
                jlvideo = null;
            }
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3??????4?????????{}", uuid, (System.currentTimeMillis() - pt));
            }
            return jlvideos.size();
        }, executor);
    }

    /**
     * ????????????
     *
     * @return
     */
    private int substituteVideo(String uuid, List<Integer> userCatids, List<Integer> hotspotCatids, List<Integer> excludeCollectid, Map<Integer, List<String>> searchVideoMap, List<String> searchVideo, boolean isABTest) {
        if (log.isDebugEnabled()) {
            log.debug("{} ??????0.3????????????????????????????????????{}", uuid, JSON.toJSONString(searchVideoMap));
        }
        long pt = System.currentTimeMillis();
        //?????????????????????????????????
        //???????????????????????????????????????????????????????????????searchVideo???????????????????????????????????????????????????????????????????????????????????????
        userCatids.forEach(d -> {
            if (searchVideoMap.containsKey(d)) {
                List<String> tmpL = searchVideoMap.get(d);
                searchVideo.addAll(tmpL);
                searchVideoMap.remove(d);
            }
        });
        //???????????????searchVideoMap??????????????????????????????????????????????????????????????????
        if (!searchVideoMap.isEmpty()) {
            searchVideoMap.values().forEach(d -> {
                searchVideo.addAll(d);
            });
            searchVideoMap.clear();
        }
        int diffCount = 7 - searchVideo.size();
        if (diffCount > 0) {
            log.warn("{} ??????0.3?????????????????????????????????????????????????????????????????? {}", uuid, diffCount);
            List<String> suppVideos = findRecommendVideosService.getVideoId(uuid, diffCount, hotspotCatids, excludeCollectid, pt + 3000, isABTest);
            if (log.isDebugEnabled()) {
                log.debug("{} ??????0.3????????????mongo???????????????{}", uuid, System.currentTimeMillis() - pt);
            }
            if (isEmpty(suppVideos) || suppVideos.size() < diffCount) {
                log.warn("??????0.3????????????(uuid={})??????????????????", uuid);
            } else {
                searchVideo.addAll(suppVideos);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("{} ??????0.3?????????????????????{}", uuid, System.currentTimeMillis() - pt);
        }
        return 0;
    }

    private void fillLoginInfo(VideoParamsDto params, List<Videos161Vo> firstVideos) {
        boolean isLogin = StringUtils.isNotBlank(params.getUserId()) && !"0".equals(params.getUserId());
        if (isLogin) {
            List<ClUserVideoInfoVo> list = clUserVideosService.findUserVideo(params.getUserId(), firstVideos.stream().map(Videos161Vo::getId).collect(Collectors.toList()));
            if (!isEmpty(list)) {
                firstVideos.forEach(d -> {
                    Optional<ClUserVideoInfoVo> op = list.stream().filter(e -> e.getVideoId().longValue() == d.getId().longValue()).findFirst();
                    if (op.isPresent()) {
                        ClUserVideoInfoVo tmp = op.get();
                        d.setCollection(tmp.getCollection());
                        d.setLove(tmp.getLove());
                    }
                });
            }
        }
    }

    //V2.5.0???????????????????????????
    private void packagNewAdvertAndVideos(FirstVideos161Vo firstVideosNewVo, List<Videos161Vo> firstVideos, Map<String, Object> adverMap, AbTestAdvParamsVo queueVo) {
        List<AdvertCodeVo> advertCodeVos = advertService.commonSearch(queueVo, adverMap);
        List<FirstVideosVo> firstVideosVos = VideoUtils.packagingNewAdvert(advertCodeVos, firstVideos);
        firstVideosNewVo.setFirstVideosVos(firstVideosVos);
    }

    /**
     * ???????????????????????????
     *
     * @param videos
     */
    private void fillGatherVideos(List<Videos161Vo> videos) {
        for (Videos161Vo vo : videos) {
            if (vo.getGatherId() != null && vo.getGatherId() > 0) {
                VideoGatherVo videoGatherVo = videoGatherService.getVideoGatherVo(vo);
                vo.setVideoGatherVo(videoGatherVo);
            }
        }
    }
}