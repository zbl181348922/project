package com.miguan.recommend.service.recommend.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.miguan.recommend.bo.BaseDto;
import com.miguan.recommend.bo.SpecialConfigDto;
import com.miguan.recommend.bo.SpecialVideoDto;
import com.miguan.recommend.bo.VideoRecommendDto;
import com.miguan.recommend.common.interceptor.BaseDtoArgumentResolver;
import com.miguan.recommend.common.util.Global;
import com.miguan.recommend.entity.mongo.IncentiveVideoHotspot;
import com.miguan.recommend.entity.mongo.VideoHotspotVo;
import com.miguan.recommend.service.BloomFilterService;
import com.miguan.recommend.service.RedisService;
import com.miguan.recommend.service.recommend.AbstractRecommendService;
import com.miguan.recommend.service.recommend.IncentiveVideoHotService;
import com.miguan.recommend.service.recommend.PredictService;
import com.miguan.recommend.service.recommend.VideoRecommendService;
import com.miguan.recommend.service.xy.FirstVideoService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.springframework.util.CollectionUtils.isEmpty;

@Slf4j
@Service(value = "specialRecommendService")
public class SpecialRecommendServiceImpl extends AbstractRecommendService implements VideoRecommendService<VideoRecommendDto> {

    private final static int MAX_FLUSH_NUM = 8;

    @Resource(name = "xyRedisDB8Service")
    private RedisService xyRedisDB8Service;
    @Resource(name = "incentiveVideoHotServiceV3New")
    private IncentiveVideoHotService incentiveVideoHotServiceV3New;
    @Resource
    private PredictService predictService;
    @Resource
    private FirstVideoService firstVideoService;
    @Resource
    private BloomFilterService bloomFilterService;

    @Override
    public void recommend(BaseDto baseDto, VideoRecommendDto recommendDto) {
        long recStart = System.currentTimeMillis();
        List<String> specialVideo = this.specialVideoRecommend(baseDto, 7);
        if (!isEmpty(specialVideo) && specialVideo.size() == recommendDto.getVideoNum()) {
            List<IncentiveVideoHotspot> jlVideo = new ArrayList<>();
            // ??????????????????
            super.getIncetiveHotVideo(baseDto, recommendDto, jlVideo, incentiveVideoHotServiceV3New, executor);
            recommendDto.setJlvideo(jlVideo.stream().map(IncentiveVideoHotspot::getVideo_id).collect(Collectors.toList()));
            jlVideo.clear();
        }

        log.debug("{} ???????????? ????????????{}", baseDto.getUuid(), System.currentTimeMillis() - recStart);
        bloomFilterService.putAll(baseDto.getUuid(), specialVideo);
        recommendDto.setSelectedVideo(specialVideo);
    }

    /**
     * ????????????
     *
     * @return
     */
    private List<String> specialVideoRecommend(BaseDto baseDto, int getNum) {
        if (StringUtils.equals(BaseDtoArgumentResolver.EMPTY_GROUP, baseDto.getSpecialGroup())) {
            log.info("{} ????????????, ??????????????????????????????????????????", baseDto.getUuid());
            return null;
        }

        // ???????????????????????????????????????
        SpecialConfigDto configDto = this.getSpecialConfigDto(baseDto.getUuid(), baseDto.getPublicInfo().getCurrentChannel());
        if (configDto == null) {
            return null;
        }
        // ???????????????????????????????????????????????????
        boolean isSort = false;
        String expId = null;
        switch (baseDto.getSpecialGroup()) {
            case "1":
                expId = "0";
                isSort = configDto.isSort1();
                break;
            case "2":
                expId = "1";
                isSort = configDto.isSort2();
                break;
            case "3":
                expId = "1";
                isSort = configDto.isSort3();
                break;
            case "4":
                expId = "3";
                isSort = configDto.isSort4();
                break;
            case "5":
                expId = "3";
                isSort = configDto.isSort5();
                break;
            default:
                expId = "0";
                isSort = false;
        }
        log.info("{} ????????????, ??????{}????????????????????????:{}", baseDto.getUuid(), expId, isSort);

        // ????????????????????????
        List<String> specialVideoList = this.getSpecialVideoList(baseDto.getUuid(), configDto.getSpecialName(), expId);
        if (isEmpty(specialVideoList)) {
            return null;
        }
        // ?????????????????????????????????????????????
        List<String> specialRecVideo = this.getSpecialVideoList(baseDto.getUuid(), specialVideoList, baseDto.getFlushNum(), getNum);
        specialVideoList.clear();
        if (isEmpty(specialRecVideo)) {
            return null;
        }

        log.info("{} ???????????? ???{}???????????????>>{}", baseDto.getUuid(), baseDto.getFlushNum(), JSONObject.toJSONString(specialRecVideo));
        if (isSort) {
            List<VideoHotspotVo> queryList = firstVideoService.getByIds(specialRecVideo);
            Map<String, Integer> videoCatMap = queryList.stream().collect(Collectors.toMap(VideoHotspotVo::getVideo_id, VideoHotspotVo::getCatid, (e, e1) -> e));
            Map<String, BigDecimal> videoPlayRates = predictService.getVideoListPlayRate(baseDto, queryList);
            specialRecVideo = predictService.videoTopK(videoPlayRates, videoCatMap, getNum, getNum, 20);
        }
        log.info("{} ???????????? ??????>>{}", baseDto.getUuid(), JSONObject.toJSONString(specialRecVideo));
        return specialRecVideo;
    }

    private List<String> getSpecialVideoList(String uuid, String specialName, String expGroupId) {
        String redisKey = String.format("video:labelCache:%s:%s", specialName, expGroupId);
        String redisValue = xyRedisDB8Service.get(redisKey);
        if (isEmpty(redisValue)) {
            log.warn("{} ???????????? ??????:{},???????????????", uuid, specialName);
            return null;
        }
        List<SpecialVideoDto> specialVideoDtoList = JSONArray.parseArray(redisValue, SpecialVideoDto.class);
        List<String> specialVideoList = specialVideoDtoList.stream().sorted((v1, v2) -> {
            return v1.getSort().compareTo(v2.getSort());
        }).map(SpecialVideoDto::getVideoId).map(String::valueOf).collect(Collectors.toList());
        specialVideoDtoList.clear();
        log.info("{} ???????????? ??????:{},??????:{}", uuid, specialName, JSONObject.toJSONString(specialVideoList));
        return specialVideoList;
    }

    private SpecialConfigDto getSpecialConfigDto(String uuid, String changeChannel) {
        // ????????????????????????
        String value = Global.getValue("children_channel_special1");
        if (isEmpty(value)) {
            log.warn("{} ???????????? ????????????????????????", uuid);
            return null;
        }

        // ??????????????????????????????????????????
        JSONObject jsonObject = JSONObject.parseObject(value);
        String specialName = null;
        for(String key : jsonObject.keySet()){
            if(jsonObject.getString(key).contains(changeChannel)){
                specialName = key;
                break;
            }
        }

        if (StringUtils.isEmpty(specialName)) {
            log.warn("{} ???????????? ??????:{}, ???????????????, ??????????????????", uuid, changeChannel);
            return null;
        }
        String sortValue = Global.getValue("special_video_sort");
        JSONObject sortJson = JSONObject.parseObject(sortValue);
        return new SpecialConfigDto(specialName, sortJson);
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param flushNum
     * @param getNum
     * @return
     */
    private List<String> getSpecialVideoList(String uuid, List<String> specialVideoList, Long flushNum, int getNum) {
        if (flushNum > MAX_FLUSH_NUM) {
            String specialWhiteUser = Global.getValue("special_video_white_user");
            if (!isEmpty(specialWhiteUser) && !isEmpty(uuid) && specialWhiteUser.contains(uuid)) {
                Long m = flushNum % MAX_FLUSH_NUM;
                flushNum = (m == 0L) ? MAX_FLUSH_NUM : m;
            } else {
                log.info("{} ???????????? ???????????????{}????????????????????????", uuid, MAX_FLUSH_NUM);
                return null;
            }
        }

        int startIndex = (flushNum.intValue() - 1) * getNum;
        int endIndex = flushNum.intValue() * getNum;
        int maxIndex = specialVideoList.size();

        if (startIndex < maxIndex) {
            return Lists.newArrayList(specialVideoList.subList(startIndex, Math.min(endIndex, maxIndex)));
        }
        log.info("{} ???????????? ????????????????????????????????????????????????", uuid);
        return null;
    }
}
