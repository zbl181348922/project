package com.miguan.ballvideo.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.miguan.ballvideo.common.constants.Constant;
import com.miguan.ballvideo.common.constants.VideoContant;
import com.miguan.ballvideo.common.enums.VideoESOptions;
import com.miguan.ballvideo.common.interceptor.argument.params.AbTestAdvParamsVo;
import com.miguan.ballvideo.common.util.Global;
import com.miguan.ballvideo.common.util.StringUtil;
import com.miguan.ballvideo.common.util.VersionUtil;
import com.miguan.ballvideo.common.util.adv.AdvUtils;
import com.miguan.ballvideo.common.util.video.VideoSQLUtils;
import com.miguan.ballvideo.common.util.video.VideoUtils;
import com.miguan.ballvideo.dynamicquery.DynamicQuery;
import com.miguan.ballvideo.entity.MarketAudit;
import com.miguan.ballvideo.entity.UserLabel;
import com.miguan.ballvideo.entity.UserLabelDefault;
import com.miguan.ballvideo.entity.VideosReport;
import com.miguan.ballvideo.mapper.*;
import com.miguan.ballvideo.rabbitMQ.util.RabbitMQConstant;
import com.miguan.ballvideo.redis.util.IPUtils;
import com.miguan.ballvideo.redis.util.RedisKeyConstant;
import com.miguan.ballvideo.repositories.UserLabelDefaultJpaRepository;
import com.miguan.ballvideo.repositories.UserLabelJpaRepository;
import com.miguan.ballvideo.repositories.VideoReportDao;
import com.miguan.ballvideo.service.*;
import com.miguan.ballvideo.vo.*;
import com.miguan.ballvideo.vo.video.FirstVideos16Vo;
import com.miguan.ballvideo.vo.video.FirstVideosNewVo;
import com.miguan.ballvideo.vo.video.RealWeightCalculateVo;
import com.miguan.ballvideo.vo.video.Videos161Vo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.cgcg.redis.core.entity.RedisLock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.miguan.ballvideo.common.constants.VideoContant.*;

/**
 * ?????????????????????ServiceImpl???
 *
 * @author xy.chen
 * @date 2019-08-09
 **/

@Slf4j
@Service("firstVideosOldService")
public class FirstVideosOldServiceImpl implements FirstVideosOldService {

    @Resource
    private FirstVideosMapper firstVideosMapper;

    @Resource
    private SmallVideosMapper smallVideosMapper;

    @Resource
    private ClUserVideosMapper clUserVideosMapper;

    @Resource
    private AdvertOldService advertOldService;

    @Resource
    private VideoReportDao videoReportDao;

    @Resource
    private RedisService redisService;

    @Resource
    private UserLabelJpaRepository userLabelJpaRepository;

    @Resource
    private VideoCacheService videoCacheService;

    @Resource
    private UserLabelDefaultJpaRepository userLabelDefaultJpaRepository;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private VideoEsService firstVideoEsItemService;

    @Resource
    private MarketAuditService marketAuditService;

    @Resource
    private DynamicQuery dynamicQuery;

    @Resource
    private AdvertService advertService;

    @Resource
    private ClUserService clUserService;

    @Resource
    private VideosCatMapper videosCatMapper;

    @Resource
    private RedisDB8Service redisDB8Service;

    @Resource
    private VideoAlbumMapper videoAlbumMapper;

    //????????????
    public static final int RANDOM_NUMBER = 1;

    /**
     * ????????????????????????
     *
     * @param
     * @return
     **/
    @Override
    public Page<FirstVideos> findMyCollection(String userId, String mobileType, String appVersion, int currentPage, int pageSize) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", userId);
        map.put("collection", 1);
        PageHelper.startPage(currentPage, pageSize);
        List<FirstVideos> firstVideoListByMyCollection = firstVideosMapper.findFirstVideoListByMyCollection(map);
        //???????????????????????????????????????
        VideoUtils.setCatName(null, firstVideoListByMyCollection, videoCacheService.getVideosCatMap(VideoContant.FIRST_VIDEO_CODE));
        //???????????????????????????????????????????????????????????????????????????
        clUserService.packagingUserAndfirstVideos(firstVideoListByMyCollection);
        //?????????????????????????????????????????????????????????bsyUrl???
        List<VideoAlbumVo> videoAlbumVos = videoAlbumMapper.findAlbumTitleByAll();
        VideoUtils.videoEncryption2(firstVideoListByMyCollection, videoAlbumVos, mobileType, appVersion);
        return (Page<FirstVideos>) firstVideoListByMyCollection;
    }

    @Override
    public Page<FirstVideos> findFirstVideosPage(Map<String, Object> params, int currentPage, int pageSize) {
        String deviceId = params.get("deviceId") == null ? null : (String) params.get("deviceId");
        List<FirstVideos> firstVideosList;
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosList = firstVideosMapper.findFirstVideosList(params);
        } else {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosList = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        if (CollectionUtils.isNotEmpty(firstVideosList)) {
            //VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosList);
            //????????????
            AdvertVo advertVo = advertOldService.queryOneByRandom(params);
            List<AdvertVo> list = new ArrayList<AdvertVo>();
            list.add(advertVo);
            for (FirstVideos firstVideosVo : firstVideosList) {
                firstVideosVo.setAdvertVoList(list);
            }
        }
        return (Page<FirstVideos>) firstVideosList;
    }

    /**
     * ??????1.3?????????
     * 1?????????????????????????????????firstLoadPosition???<3,??????2?????????+1????????????????????????firstLoadPosition???> 3,?????????????????????firstLoadPosition-1????????????+1??????
     * 2???????????????????????????????????????secondLoadPosition-1????????????+1??????
     *
     * @param params
     * @param currentPage
     * @return
     */
    @Override
    public FirstVideosNewVo firstVideosList13(
            Map<String, Object> params, int currentPage, int flag) {
        final FirstVideosNewVo firstVideosNewVo = new FirstVideosNewVo();
        //??????????????????
        final AdvertVo advertVo = advertOldService.queryOneByRandom(params);
        final int pageSize = VideoUtils.getPageSize(advertVo, currentPage, VideoContant.firstVideo_default_pageSize, flag);
        List<FirstVideos> firstVideosList = null;
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosList = firstVideosMapper.findFirstVideosList(params);
        } else {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosList = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        /*if (CollectionUtils.isNotEmpty(firstVideosList)) {
            VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosList);
        }*/
        firstVideosNewVo.setAdvertVo(advertVo);
        firstVideosNewVo.setPage((Page<FirstVideos>) firstVideosList);
        return firstVideosNewVo;
    }

    @Override
    public List<FirstVideos> findFirstVideosList(Map<String, Object> params) {
        List<FirstVideos> firstVideosList;
        //?????????????????????
        String appPackage = MapUtils.getString(params, "appPackage");
        List<String> catIds = videosCatMapper.queryCatIdsList(appPackage);
        params.put("otherCatIds", catIds);
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            String id = MapUtils.getString(params, "id");
            if (StringUtils.isEmpty(id) || "0".equals(id)) {
                log.error("searchDebug0429(findFirstVideosList):" + params.toString());
                return null;
            }
            firstVideosList = firstVideosMapper.findFirstVideosList(params);
        } else {
            firstVideosList = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        if (CollectionUtils.isEmpty(firstVideosList)) {
            String videoId = (String) params.get("id");
            String key = RedisKeyConstant.SPECIAL_VIDEO_LIST + videoId;
            String value = redisDB8Service.get(key);
            if (StringUtils.isNotEmpty(value)) {
                Videos161Vo vo = JSON.parseObject(value, Videos161Vo.class);
                FirstVideos video = new FirstVideos();
                BeanUtils.copyProperties(vo,video);
                firstVideosList.add(video);
            }
        }
        if (CollectionUtils.isNotEmpty(firstVideosList)) {
            //VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosList);
            //???????????????????????????????????????
            VideoUtils.setCatName(null, firstVideosList, videoCacheService.getVideosCatMap(VideoContant.FIRST_VIDEO_CODE));
            //???????????????????????????????????????????????????????????????????????????
            clUserService.packagingUserAndfirstVideos(firstVideosList);
            //????????????
            List<AdvertVo> advertVos = videoCacheService.getBaseAdvertList(params);
            for (FirstVideos firstVideosVo : firstVideosList) {
                firstVideosVo.setAdvertVoList(AdvUtils.computer(advertVos, RANDOM_NUMBER));
            }
            String mobileType = MapUtils.getString(params, "mobileType");
            String appVersion = MapUtils.getString(params, "appVersion");
            //?????????????????????????????????????????????????????????bsyUrl???
            List<VideoAlbumVo> videoAlbumVos = videoAlbumMapper.findAlbumTitleByAll();
            VideoUtils.videoEncryption2(firstVideosList, videoAlbumVos, mobileType, appVersion);
        }
        return firstVideosList;
    }

    @Override
    public FirstVideos findFirstVideosDetail(Map<String, Object> params) {
        List<FirstVideos> firstVideosList;
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            firstVideosList = firstVideosMapper.findFirstVideosDetailList(params);
        } else {
            firstVideosList = firstVideosMapper.findFirstVideosDetailByUserId(params);
        }
        if (firstVideosList.size() > 0) {
            //???????????????????????????????????????????????????????????????????????????
            clUserService.packagingUserAndfirstVideos(firstVideosList);
            String mobileType = MapUtils.getString(params, "mobileType");
            String appVersion = MapUtils.getString(params, "appVersion");
            //?????????????????????????????????????????????????????????bsyUrl???
            List<VideoAlbumVo> videoAlbumVos = videoAlbumMapper.findAlbumTitleByAll();
            VideoUtils.videoEncryption2(firstVideosList, videoAlbumVos, mobileType, appVersion);
            return firstVideosList.get(0);
        }
        return null;
    }

    @Override
    public List<FirstVideos> findFirstVideosByUserId(Long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        return firstVideosMapper.findFirstVideosListByUserId(params);
    }

    @Override
    public int updatevideoAuthorAndbsyHeadUrlByUserId(String author, String bsyHeadUrl, Long userId) {
        return firstVideosMapper.updatevideoAuthorAndbsyHeadUrlByUserId(author, bsyHeadUrl, userId);
    }

    /**
     * 1.???????????????????????????????????????????????????2.????????????????????????????????????????????????????????????
     * ??????????????????????????????????????????????????????????????????????????????????????????
     *
     * @param params
     * @return
     */
    @Override
    public boolean updateVideosCount(Map<String, Object> params) {
        String type = MapUtils.getString(params, "type");
        String id = MapUtils.getString(params, "id");
        String userId = MapUtils.getString(params, "userId");
        String opType = MapUtils.getString(params, "opType");
        String realWeight = MapUtils.getString(params, "realWeight");
        if (StringUtil.isBlank(userId)) userId = "0";
        if (StringUtil.isBlank(id)) return false;
        if (WATCH_CODE.equals(opType)) {
            updateVideoReportCount(id, type, opType);
            if (FIRST_VIDEO_CODE.equals(type)) {
                firstVideosMapper.updateFirstVideosCount(params);
            } else if (SMALL_VIDEO_CODE.equals(type)) {
                smallVideosMapper.updateSmallVideosCount(params);
            }
        } else if (REPORT_CODE.equals(opType)) {
            updateVideoReportCount(id, type, opType);
        } else if (LOVE_CODE.equals(opType) || CANCEL_LOVE_CODE.equals(opType)) {
            updateUserVideoInfo(userId, id, type, opType);
            if (FIRST_VIDEO_CODE.equals(type)) {
                firstVideosMapper.updateFirstVideosCount(params);
            } else if (SMALL_VIDEO_CODE.equals(type)) {
                smallVideosMapper.updateSmallVideosCount(params);
            }
        } else if (NO_INTEREST_CODE.equals(opType)) {
            updateUserVideoInfo(userId, id, type, opType);
        } else if (COLLECTION_CODE.equals(opType) || CANCEL_COLLECTION_CODE.equals(opType)
                || SHARE_CODE.equals(opType) || PLAY_ALL_CODE.equals(opType)
                || PLAY_COUNt_CODE.equals(opType) || StringUtils.isNotEmpty(realWeight)) {
            //??????????????????opType=PLAY_ALL_CODE?????????????????????????????? add shixh1018
            if (FIRST_VIDEO_CODE.equals(type)) {
                Integer randomNum = getRandom(50, 200);  //??????50-200????????????
                params.put("randomNum", randomNum);

                firstVideosMapper.updateFirstVideosCount(params);
            } else if (SMALL_VIDEO_CODE.equals(type)) {
                smallVideosMapper.updateSmallVideosCount(params);
            }
            if (COLLECTION_CODE.equals(opType) || CANCEL_COLLECTION_CODE.equals(opType)) {
                updateUserVideoInfo(userId, id, type, opType);
            }
        } else {
            return false;
        }
        //?????????????????????????????????????????????
        if (FIRST_VIDEO_CODE.equals(type)) {
            if (WATCH_CODE.equals(opType) || LOVE_CODE.equals(opType) || CANCEL_LOVE_CODE.equals(opType) || SHARE_CODE.equals(opType) ||
                    COLLECTION_CODE.equals(opType) || CANCEL_COLLECTION_CODE.equals(opType) || StringUtils.isNotEmpty(realWeight)) {
                //String json = VideoESOptions.videoAdd.name() + RabbitMQConstant._MQ_ + id;
                //rabbitTemplate.convertAndSend(RabbitMQConstant.VIDEOS_ES_SEARCH_EXCHANGE, RabbitMQConstant.VIDEOS_ES_SEARCH_KEY, json);
                firstVideoEsItemService.update(id, VideoESOptions.videoUpdate.name());
            }
        }
        return true;
    }

    /**
     * ??????start~end????????????
     *
     * @param start
     * @param end
     * @return
     */
    private Integer getRandom(int start, int end) {
        Random random = new Random();
        return random.nextInt(end - start) + start + 1;
    }

    /**
     * ?????????????????????????????????
     *
     * @param params
     * @return
     */
    @Override
    public boolean addRedisVideosWatchInfo(Map<String, Object> params) {
        String id = MapUtils.getString(params, "id");
        if (StringUtil.isBlank(id)) return false;
        String ip = IPUtils.getHostAddress();
        String key = RedisKeyConstant.VIDEO_WATCH_IDS + ip;
        Long lpush = redisService.lpush(key, id);
        Integer videoWatchLimit = Global.getInt("video_watch_limit");
        if (lpush >= videoWatchLimit) {
            List<String> lrange = redisService.lrange(key, 0, 999);
            if (CollectionUtils.isNotEmpty(lrange)) {
                redisService.ltrim(key, lrange.size(), -1);
                updateVideoWatchInfo(lrange, 0);
            }
        }
        return true;
    }

    /**
     * @param lrange
     * @param type   0-?????????  1-????????????
     */
    @Transactional
    private void updateVideoWatchInfo(List<String> lrange, int type) {
        Map<Long, List<Long>> addMap = VideoUtils.getLongListMap(lrange);
        for (Map.Entry<Long, List<Long>> mapEntry : addMap.entrySet()) {
            Long mapKey = mapEntry.getKey();
            List<Long> mapValue = mapEntry.getValue();
            if (type == 0) {
                String updateIds = VideoUtils.getString(mapValue);
                Map<String, Object> param = new HashMap<>();
                param.put("updateNum", mapKey);
                param.put("ids", updateIds);
                try {
                    //???????????????ES???????????????
                    firstVideosMapper.updateFirstVideosCountBatch(param);
                    firstVideoEsItemService.update(updateIds, VideoESOptions.videoUpdate.name());
                } catch (Exception e) {
                    addRedisVideoErrorInfo("firstVideoUpdateError", mapKey, mapValue);
                }
            }
            //???????????????????????????
            updateVideoReportCountBatch(mapValue, mapKey);
        }
    }

    private void addRedisVideoErrorInfo(String key, Long mapKey, List<Long> mapValue) {
        log.info("????????????????????????" + IPUtils.getHostAddress());
        String errorKey = RedisKeyConstant.VIDEO_WATCH_IDS + key;
        for (int i = 0; i < mapKey.intValue(); i++) {
            for (Long videoId : mapValue) {
                redisService.lpush(errorKey, String.valueOf(videoId));
            }
        }
    }

    /**
     * ???????????????????????????
     *
     * @param ids
     */
    @Transactional
    public void updateVideoReportCountBatch(List<Long> ids, Long updateNum) {
        List<VideosReport> videosReportList = null;
        try {
            videosReportList = videoReportDao.findByVideoId(ids);
        } catch (Exception e) {
            log.error("??????videosReport????????????:[{}]", e.getMessage());
            addRedisVideoErrorInfo("videoReportUpdateError", updateNum, ids);
        }
        List<Long> addIds = Lists.newArrayList();
        for (Long videoId : ids) {
            boolean existId = false;
            if (CollectionUtils.isNotEmpty(videosReportList)) {
                for (VideosReport videosReport : videosReportList) {
                    if (videosReport.getVideoId().equals(videoId)) {
                        existId = true;
                    }
                }
            }
            if (existId) {
                addIds.add(videoId);
            } else {
                try {
                    VideosReport videosReport = new VideosReport();
                    videosReport.setVideoId(videoId);
                    videosReport.setVideoType(10);
                    videosReport.setWatchCount(updateNum);
                    videosReport.setReportCount(0L);
                    videosReport.setCreateDate(new Date());
                    videoReportDao.save(videosReport);
                } catch (Exception e) {
                    log.error("??????videosReport????????????:[{}]", e.getMessage());
                    List<Long> errorId = Lists.newArrayList();
                    errorId.add(videoId);
                    addRedisVideoErrorInfo("videoReportUpdateError", updateNum, errorId);
                }
            }
        }
        if (CollectionUtils.isNotEmpty(addIds)) {
            try {
                videoReportDao.updateVideosWatchCntBatch(updateNum, addIds);
            } catch (Exception e) {
                log.error("??????videosReport????????????:[{}]", e.getMessage());
                addRedisVideoErrorInfo("videoReportUpdateError", updateNum, addIds);
            }

        }
    }

    /**
     * ?????????????????????????????????????????????????????????????????????
     */
    @Scheduled(cron = "0 */30 * * * ?")
    @Transactional
    public void updateWatchNumErrorDatas() {
        RedisLock redisLock = new RedisLock(RedisKeyConstant.VIDEO_WATCH_CLEAR_WRONG_DATAS_LOCK, RedisKeyConstant.VIDEO_WATCH_CLEAR_WRONG_DATAS_SECONDS);
        if (redisLock.lock()) {
            boolean flag1 = true;
            boolean flag2 = true;
            LocalTime nowTime = LocalTime.now();
            int batchSize = nowTime.isAfter(LocalTime.parse("01:00:00")) && nowTime.isBefore(LocalTime.parse("15:00:00")) ? 1000 : 100;
            int num = 0;
            try {
                do {
                    String errorKey1 = RedisKeyConstant.VIDEO_WATCH_IDS + "firstVideoUpdateError";
                    List<String> datas1 = flag1 ? redisService.lrange(errorKey1, 0, 999) : null;
                    if (CollectionUtils.isNotEmpty(datas1)) {
                        redisService.ltrim(errorKey1, datas1.size(), -1);
                        updateVideoWatchInfo(datas1, 0);
                    } else {
                        flag1 = false;
                    }
                    String errorKey2 = RedisKeyConstant.VIDEO_WATCH_IDS + "videoReportUpdateError";
                    List<String> datas2 = flag2 ? redisService.lrange(errorKey2, 0, 999) : null;
                    if (CollectionUtils.isNotEmpty(datas2)) {
                        redisService.ltrim(errorKey2, datas2.size(), -1);
                        updateVideoWatchInfo(datas2, 1);
                    } else {
                        flag2 = false;
                    }
                    num++;
                } while ((flag1 || flag2) && num < batchSize);
            } catch (Exception e) {
                log.error("???????????????????????????{}", e.getMessage(), e);
            } finally {
                redisLock.unlock();
            }
        } else {
            log.info("????????????????????????????????????:????????????redis??????");
        }
    }

    /**
     * ????????????????????????
     *
     * @param id   ??????id
     * @param type ????????????
     */
    public void updateVideoReportCount(String id, String type, String opType) {
        VideosReport videosReport = videoReportDao.findByVideoIdAndVideoType(Long.valueOf(id), Integer.valueOf(type));
        if (videosReport == null) {
            videosReport = new VideosReport();
            videosReport.setVideoId(Long.valueOf(id));
            videosReport.setVideoType(Integer.valueOf(type));
            videosReport.setWatchCount(1L);
            videosReport.setReportCount(0L);
            if (!WATCH_CODE.equals(opType)) {
                videosReport.setWatchCount(0L);
                videosReport.setReportCount(1L);
            }
            videosReport.setCreateDate(new Date());
            videoReportDao.save(videosReport);
        } else {
            if (WATCH_CODE.equals(opType)) {
                videoReportDao.updateVideosWatchCnt(Long.valueOf(id), Integer.valueOf(type));
            } else {
                videoReportDao.updateVideosReportCnt(Long.valueOf(id), Integer.valueOf(type));
            }
        }
    }

    /**
     * ?????????????????????????????????
     *
     * @param userId ??????id
     * @param id     ??????id
     * @param type   ????????????
     * @param opType ???????????? 10--?????? 20--?????? 30--?????? 40--???????????? 50--???????????? 60-- ???????????? 70--?????? 80--??????
     * @return
     */
    public void updateUserVideoInfo(String userId, String id, String type, String opType) {
        if (StringUtils.isBlank(type)) return;
        Map<String, Object> paraMap = new HashMap<>();
        paraMap.put("userId", userId);
        paraMap.put("videoId", id);
        paraMap.put("videoType", type);
        List<ClUserVideosVo> clUserVideosList = clUserVideosMapper.findClUserVideosList(paraMap);
        ClUserVideosVo clUserVideosVo = new ClUserVideosVo();
        clUserVideosVo.setUserId(Long.valueOf(userId));
        clUserVideosVo.setVideoId(Long.valueOf(id));
        clUserVideosVo.setVideoType(Integer.valueOf(type));
        clUserVideosVo.setCollectionTime(new Date());
        if (clUserVideosList == null || clUserVideosList.size() == 0) {
            clUserVideosVo.setCollection("0");
            clUserVideosVo.setLove("0");
            clUserVideosVo.setInterest("0");
            if (COLLECTION_CODE.equals(opType)) {
                clUserVideosVo.setCollection("1");
            } else if (LOVE_CODE.equals(opType)) {
                clUserVideosVo.setLove("1");
            }
            try {
                clUserVideosMapper.saveClUserVideos(clUserVideosVo);
            } catch (DuplicateKeyException e) {
                log.info("Cl_User_Videos??????????????????(" + userId + "_" + id + "_" + type + ")");
            }
        } else {
            clUserVideosVo.setOpType(opType);
            clUserVideosMapper.updateClUserVideos(clUserVideosVo);
        }
    }

    @Override
    public int batchDelCollections(String[] collectionIds) {
        //????????????????????????
        clUserVideosMapper.batchDelCollections(collectionIds);
        //?????????????????????????????????
        List<ClUserVideosVo> collectionsList = clUserVideosMapper.findCollectionsList(collectionIds);
        List<String> firstList = new ArrayList<>();
        List<String> smallList = new ArrayList<>();
        for (ClUserVideosVo clUserVideosVo : collectionsList) {
            String videoType = String.valueOf(clUserVideosVo.getVideoType());
            if (FIRST_VIDEO_CODE.equals(videoType)) {
                firstList.add(String.valueOf(clUserVideosVo.getVideoId()));
            } else if (SMALL_VIDEO_CODE.equals(videoType)) {
                smallList.add(String.valueOf(clUserVideosVo.getVideoId()));
            }
        }
        //?????????????????????????????????
        if (firstList.size() > 0) {
            String[] firstStr = new String[firstList.size()];
            firstList.toArray(firstStr);
            clUserVideosMapper.batchUpdateFirstvideos(firstStr);
        }

        //??????????????????????????????
        if (smallList.size() > 0) {
            String[] smallStr = new String[smallList.size()];
            smallList.toArray(smallStr);
            clUserVideosMapper.batchUpdateSmallvideos(smallStr);
        }
        return 1;
    }

    @Override
    public Map<String, Object> getRandomVideosAndAdvert(AbTestAdvParamsVo queueVo, Map<String, Object> params) {
        String type = (String) params.get("type");
        String catId = (String) params.get("catId");
        String queryNumber = (String) params.get("queryNumber");
        String positionType = (String) params.get("positionType");
        String mobileType = (String) params.get("mobileType");
        String channelId = (String) params.get("channelId");
        String videoType = (String) params.get("videoType");
        String permission = (String) params.get("permission");
        String marketChannelId = (String) params.get("marketChannelId");
        String appVersion = (String) params.get("appVersion");
        String appPackage = (String) params.get("appPackage");
        String gatherId = (String) params.get("gatherId");
        Map<String, Object> responseMap = new HashMap<>();
        Map<String, Object> fistVideoMap = new HashMap<>();
        Map<String, Object> advertMap = new HashMap<>();
        fistVideoMap.put("state", "1");//??????????????????
        fistVideoMap.put("gatherId", gatherId);
        //????????????????????????  ??????????????????????????????????????????????????????????????????
        if (type.equals(FIRST_VIDEO_CODE)) {
            if ("20".equals(videoType)) {
                fistVideoMap.put("catId", catId);
            }
            if (VersionUtil.compareIsHigh(appVersion, Constant.APPVERSION_259)) {
                if (StringUtils.isNotEmpty(catId) && Integer.parseInt(catId) > 0) {
                    fistVideoMap.put("catId", catId);
                }
            }
            //??????????????????????????????????????????
            marketAudit18(marketChannelId, appVersion, fistVideoMap);
            //?????????????????????
            List<Long> otherCatIds = (List<Long>) MapUtils.getObject(params, "otherCatIds");
            List<String> catIds = videosCatMapper.queryCatIdsList(appPackage);
            if (CollectionUtils.isNotEmpty(catIds)) {
                if (CollectionUtils.isEmpty(otherCatIds)) {
                    otherCatIds = new ArrayList<>();
                }
                for (String id : catIds) {
                    Long aLong = Long.valueOf(id);
                    otherCatIds.add(aLong);
                }
            }
            fistVideoMap.put("otherCatIds", otherCatIds);
            List<FirstVideos> firstVideosList = videoCacheService.getFirstVideos(fistVideoMap, Integer.parseInt(queryNumber));
            if (CollectionUtils.isNotEmpty(firstVideosList)) {
                //VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosList);
                //???????????????????????????????????????
                VideoUtils.setCatName(null, firstVideosList, videoCacheService.getVideosCatMap(VideoContant.FIRST_VIDEO_CODE));
                //?????????????????????????????????????????????????????????bsyUrl???
                List<VideoAlbumVo> videoAlbumVos = videoAlbumMapper.findAlbumTitleByAll();
                VideoUtils.videoEncryption2(firstVideosList, videoAlbumVos, mobileType, appVersion);
                advertMap.put("positionType", positionType);
                advertMap.put("mobileType", mobileType);
                if (StringUtils.isBlank(channelId)) {
                    channelId = "xysp_guanwang";
                }
                advertMap.put("marketChannelId", marketChannelId);
                advertMap.put("channelId", channelId);
                advertMap.put("permission", permission);
                advertMap.put("appVersion", appVersion);
                advertMap.put("appPackage", appPackage);
                boolean newFlag = VersionUtil.compareIsHigh(appVersion, Constant.APPVERSION_249);
                if (newFlag) {
                    //V2.5.0??????????????????
                    List<AdvertCodeVo> advertCodeVos = advertService.commonSearch(queueVo, advertMap);
                    for (FirstVideos firstVideosVo : firstVideosList) {
                        firstVideosVo.setAdvertCodeVos(advertCodeVos);
                    }
                } else {
                    boolean flag = VersionUtil.compareIsHigh(appVersion, Constant.APPVERSION_220);
                    List<AdvertVo> advertList = advertOldService.getAdvertsBySection(advertMap);
                    for (FirstVideos firstVideosVo : firstVideosList) {
                        if (flag) {
                            if (CollectionUtils.isNotEmpty(advertList)) {
                                int adType = advertList.get(0).getType();
                                if (adType == 0) {
                                    firstVideosVo.setAdvertVoList(AdvUtils.computer(advertList, advertList.size()));
                                } else {
                                    firstVideosVo.setAdvertVoList(advertList);
                                }
                            }
                        } else {
                            firstVideosVo.setAdvertVoList(AdvUtils.computer(advertList, 1));
                        }
                    }
                }
                clUserService.packagingUserAndfirstVideos(firstVideosList);
                responseMap.put("data", firstVideosList);
            } else {
                responseMap.put("data", null);
            }
        }
        return responseMap;
    }

    @Override
    public FirstVideosNewVo findFirstVideosPage13(Map<String, Object> params, int currentPage, int flag) {
        final FirstVideosNewVo firstVideosNewVo = new FirstVideosNewVo();
        List<FirstVideos> firstVideosVos;
        //????????????
        final AdvertVo advertVo = advertOldService.queryOneByRandom(params);
        final int pageSize = VideoUtils.getPageSize(advertVo, currentPage, VideoContant.videoDetail_default_pageSize, flag);
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosVos = firstVideosMapper.findFirstVideosList(params);
        } else {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosVos = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        /*if (CollectionUtils.isNotEmpty(firstVideosVos)) {
            VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosVos);
        }*/
        firstVideosNewVo.setAdvertVo(advertVo);
        firstVideosNewVo.setPage((Page<FirstVideos>) firstVideosVos);
        return firstVideosNewVo;
    }


    @Override
    public FirstVideos16Vo firstVideosList16(Map<String, Object> params) {
        final FirstVideos16Vo firstVideosNewVo = new FirstVideos16Vo();
        final Page<FirstVideos> page = new Page();
        final List<FirstVideos> pageResult = page.getResult();
        //??????5??????
        final List<AdvertVo> advertVos = advertOldService.queryByRandom(params, 5);
        List<FirstVideos> firstVideosList;
        final int pageNumber = Global.getInt("page_number");
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            firstVideosList = videoCacheService.getFirstVideos(params, pageNumber);
        } else {
            firstVideosList = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        if (CollectionUtils.isNotEmpty(firstVideosList)) {
            //VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosList);
            pageResult.addAll(firstVideosList);
        }
        firstVideosNewVo.setAdvertVos(advertVos);
        firstVideosNewVo.setPage((Page<FirstVideos>) pageResult);
        return firstVideosNewVo;
    }

    @Override
    public FirstVideos16Vo firstRecommendVideosList16(Map<String, Object> params) {

        final FirstVideos16Vo firstVideosNewVo = new FirstVideos16Vo();
        final Page<FirstVideos> page = new Page();
        final List<FirstVideos> firstVideosList = page.getResult();

        final String deviceId = MapUtils.getString(params, "deviceId");
        if (StringUtils.isEmpty(deviceId)) {
            //????????????????????????????????????????????????
            getDefaultRecommendVideosList(params, firstVideosList);
        } else {
            //????????????ID??????????????????
            final UserLabel userLabel = userLabelJpaRepository.findTopByDeviceId(deviceId);
            if (userLabel == null) {
                //????????????????????????????????????????????????
                getDefaultRecommendVideosList(params, firstVideosList);
            } else {
                //??????????????????????????????
                final Long catId1 = userLabel.getCatId1();
                final Long catId2 = userLabel.getCatId2();
                //??????????????????????????????????????????
                getRecommendVideosList(params, firstVideosList, catId1, catId2);
            }
        }
        /*if (CollectionUtils.isNotEmpty(firstVideosList)) {
            //???????????????????????????????????????
            VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosList);
        }*/
        //??????5?????????
        params.remove("queryNumber");//??????????????????????????????????????????????????????
        List<AdvertVo> advertVos = advertOldService.queryByRandom(params, 5);
        firstVideosNewVo.setAdvertVos(advertVos);
        firstVideosNewVo.setPage(page);
        return firstVideosNewVo;
    }

    /**
     * ????????????????????????????????????????????????
     *
     * @param params
     * @param firstVideosList
     */
    private void getDefaultRecommendVideosList(Map<String, Object> params, List<FirstVideos> firstVideosList) {
        UserLabelDefault userLabelDefault;
        final String key = "userLabelDefault:default";
        if (redisService.exits(key)) {
            final String jsonStr = redisService.get(key, String.class);
            userLabelDefault = JSONObject.parseObject(jsonStr, UserLabelDefault.class);
        } else {
            userLabelDefault = userLabelDefaultJpaRepository.findTopByChannelId(UserLabelDefaultServiceImpl.DEFAULT_USER_LABEL);
            redisService.set(key, JSONObject.toJSONString(userLabelDefault), 5 * 60);
        }
        //??????????????????????????????
        final Long catId1 = userLabelDefault.getCatId1();
        final Long catId2 = userLabelDefault.getCatId2();
        //??????????????????????????????????????????
        getRecommendVideosList(params, firstVideosList, catId1, catId2);
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param params
     * @param firstVideosList
     * @param catId1
     * @param catId2
     */
    private void getRecommendVideosList(Map<String, Object> params, List<FirstVideos> firstVideosList, Long catId1, Long catId2) {

        List<FirstVideos> firstVideosList1;
        List<FirstVideos> firstVideosList2;
        List<FirstVideos> firstVideosList3;

        //??????????????????????????????
        final int firstLableValue = Global.getInt("first_label_value");//????????????????????????
        params.put("catId", catId1);
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            firstVideosList1 = videoCacheService.getFirstVideos(params, firstLableValue);
        } else {
            params.put("queryNumber", firstLableValue);
            firstVideosList1 = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        firstVideosList.addAll(firstVideosList1);

        //??????????????????????????????
        final int secondLableValue = Global.getInt("second_label_value");//????????????????????????
        params.remove("queryNumber");
        params.put("catId", catId2);
        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            firstVideosList2 = videoCacheService.getFirstVideos(params, secondLableValue);
        } else {
            params.put("queryNumber", secondLableValue);
            firstVideosList2 = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        firstVideosList.addAll(firstVideosList2);

        //??????????????????????????????
        final int otherLableValue = Global.getInt("other_label_value");//????????????????????????
        params.remove("queryNumber");
        params.remove("catId");
        final List<Long> list = new ArrayList<>();
        list.add(catId1);
        list.add(catId2);
        params.put("otherCatIds", list);

        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            firstVideosList3 = videoCacheService.getFirstVideos(params, otherLableValue);
        } else {
            params.put("queryNumber", otherLableValue);
            firstVideosList3 = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        firstVideosList.addAll(firstVideosList3);
    }


    @Override
    public FirstVideos16Vo findFirstVideosPage16(Map<String, Object> params, int currentPage, int flag) {
        final FirstVideos16Vo firstVideosNewVo = new FirstVideos16Vo();
        List<FirstVideos> firstVideosVos;

        if (flag == 1) currentPage = 1;//????????????????????????????????????????????????flag?????????
        final int pageSize = Global.getInt("page_number");

        //??????????????????????????????????????????
        String appVersion = MapUtils.getString(params, "appVersion");
        marketAudit18(MapUtils.getString(params, "marketChannelId"), appVersion, params);

        if (MapUtils.getString(params, "userId") == null || MapUtils.getString(params, "userId").equals("0")) {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosVos = firstVideosMapper.findFirstVideosList(params);
        } else {
            PageHelper.startPage(currentPage, pageSize);
            firstVideosVos = firstVideosMapper.findFirstVideosListByUserId(params);
        }
        if (CollectionUtils.isNotEmpty(firstVideosVos)) {
            //VideoUtils.cntFirstVideoLoveAndWatchNum(firstVideosVos);
            //???????????????????????????????????????
            VideoUtils.setCatName(null, firstVideosVos, videoCacheService.getVideosCatMap(VideoContant.FIRST_VIDEO_CODE));
        }
        //??????5??????
        params.remove("state");
        List<AdvertVo> advertList = advertOldService.getAdvertsBySection(params);
        if (CollectionUtils.isNotEmpty(advertList)) {
            AdvertVo advertVo = advertList.get(0);
            int type = advertVo.getType();
            if (type == 0) {
                List<AdvertVo> list = AdvUtils.computer(advertList, 5);
                firstVideosNewVo.setAdvertVos(list);
            } else {
                firstVideosNewVo.setAdvertVos(advertList);
            }
        }
        firstVideosNewVo.setPage((Page<FirstVideos>) firstVideosVos);
        return firstVideosNewVo;
    }

    @Override
    public boolean updateVideosCountSendMQ(Map<String, Object> params) {
        String json = JSON.toJSONString(params);
        String type = MapUtils.getString(params, "type");
        String opType = MapUtils.getString(params, "opType");
        String appVersion = MapUtils.getString(params, "appVersion");
        if (VideoContant.FIRST_VIDEO_CODE.equals(type) && VideoContant.WATCH_CODE.equals(opType)) {
            rabbitTemplate.convertAndSend(RabbitMQConstant.VIDEO_WATCH_UPDATECOUNT_EXCHANGE, RabbitMQConstant.VIDEO_WATCH_UPDATECOUNT_KEY, json);
            boolean isHigh = !VersionUtil.compareIsHigh(Constant.APPVERSION_280, appVersion);
            if(!isHigh){
                rabbitTemplate.convertAndSend(RabbitMQConstant.VIDEO_CLICK_CAT_POOL_EXCHANGE, RabbitMQConstant.VIDEO_CLICK_CAT_POOL_KEY, json);
            }
        } else {
            rabbitTemplate.convertAndSend(RabbitMQConstant.VIDEO_UPDATECOUNT_EXCHANGE, RabbitMQConstant.VIDEO_UPDATECOUNT_KEY, json);
        }
        return true;
    }

    @Override
    public void setRealWeightRedis(Long videoId) {
        if (videoId == null || videoId == 0) return;
        String key = RedisKeyConstant.REAL_WEIGHT_KEY;
        if (redisService.exits(key)) {
            redisService.append(key, "," + videoId, -1);
        } else {
            redisService.append(key, videoId + "", -1);
        }
    }

    @Override
    public void updateVideoRealWeightByRedis() {
        RedisLock redisLock = new RedisLock(RedisKeyConstant.REAL_WEIGHT_LOCK_KEY, RedisKeyConstant.REAL_WEIGHT_LOCK_SECONDS);
        if (redisLock.lock()) {
            String videoIds = redisService.get(RedisKeyConstant.REAL_WEIGHT_KEY);
            if (StringUtils.isNotEmpty(videoIds)) {
                redisService.del(RedisKeyConstant.REAL_WEIGHT_KEY);
                List<String> videoIdList = Arrays.asList(videoIds.split(","));
                videoIdList = Lists.newArrayList(Sets.newHashSet(videoIdList));//??????
                log.info("????????????????????????Ids:" + videoIdList.size());
                List<List<String>> ids_batch = Lists.partition(videoIdList, 500);
                for (List<String> ids : ids_batch) {
                    updateVideoRealWeight(ids);
                }
            }
        } else {
            log.info("??????????????????????????????:????????????redis??????");
        }
    }

    @Override
    public void updateVideosRealWeight(Map<Long, Long> params) {
        List<String> ids = new ArrayList<>();
        String sql = VideoSQLUtils.getBatchUpdateSQL(params, ids);
        //??????DB
        firstVideosMapper.updateFirstVideosRealWeight(sql);
        if (CollectionUtils.isNotEmpty(ids)) {
            //??????ES
            String ids_str = String.join(",", ids);
            firstVideoEsItemService.update(ids_str, VideoESOptions.videoUpdate.name());
        }
        log.info("-------??????????????????????????????--------");
    }


    public void updateVideoRealWeight(List<String> newList) {
        String showedIds = newList.stream().collect(Collectors.joining(","));
        //List<RealWeightCalculateVo> realWeightCalculateVos = firstVideosMapper.calculateByIds(showedIds);
        String sql = VideoSQLUtils.calculateByIds(showedIds);
        List<RealWeightCalculateVo> realWeightCalculateVos = dynamicQuery.nativeQueryList(RealWeightCalculateVo.class, sql);
        if (CollectionUtils.isEmpty(realWeightCalculateVos)) return;
        int num = Global.getInt("realWeight_batchUpdate_num");
        List<List<RealWeightCalculateVo>> datas_batch = Lists.partition(realWeightCalculateVos, num);
        for (List<RealWeightCalculateVo> group : datas_batch) {
            Map<Long, Long> maps = group.stream().collect(Collectors.toMap(RealWeightCalculateVo::getId, RealWeightCalculateVo::getRealWeightCalculate, (key1, key2) -> key2));
            sendToMQ(maps);
        }
    }

    private void sendToMQ(Map<Long, Long> id_realWeight) {
        int state = Global.getInt("realWeight_batchUpdate_state");//1-?????????0-?????????
        if (state == 0) {
            for (Map.Entry<Long, Long> entry : id_realWeight.entrySet()) {
                Map<String, Object> params = new HashMap<>();
                params.put("id", entry.getKey());
                params.put("type", "10");
                params.put("realWeight", entry.getValue());
                updateVideosCountSendMQ(params);
            }
        } else {
            String json = JSON.toJSONString(id_realWeight);
            rabbitTemplate.convertAndSend(RabbitMQConstant.VIDEO_REALWEIGHT_UPDATE_EXCHANGE, RabbitMQConstant.VIDEO_REALWEIGHT_UPDATE_KEY, json);
        }
    }


    //V1.8.0??????????????????
    private void marketAudit18(String marketChannelId, String appVersion, Map<String, Object> params) {
        MarketAudit marketAudit = marketAuditService.getCatIdsByChannelIdAndAppVersion(marketChannelId, appVersion);
        if (marketAudit != null) {
            if (StringUtils.isNotEmpty(marketAudit.getCatIds())) {
                String[] split = marketAudit.getCatIds().split(",");
                List<Long> list = new ArrayList<>();
                for (int i = 0; i < split.length; i++) {
                    list.add(Long.valueOf(split[i]));
                }
                params.put("otherCatIds", list);
            }
            //v2.1.0
            if (StringUtils.isNotEmpty(marketAudit.getGatherIds())) {
                params.put("gatherIds", marketAudit.getGatherIds());
            }
        }
    }

}