package com.miguan.laidian.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.CaseFormat;
import com.miguan.laidian.common.util.DateUtil;
import com.miguan.laidian.entity.*;
import com.miguan.laidian.mapper.LdBuryingPointAdditionalMapper;
import com.miguan.laidian.mapper.LdBuryingPointMapper;
import com.miguan.laidian.mapper.LdBuryingUserVideosMapper;
import com.miguan.laidian.redis.util.RedisKeyConstant;
import com.miguan.laidian.repositories.LdBuryingPointUserJpaRepository;
import com.miguan.laidian.service.SmallVideoService;
import com.miguan.laidian.service.UserBuriedPointService;

import com.miguan.laidian.vo.LdBuryingPointEveryVo;
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.cgcg.redis.core.entity.RedisLock;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class UserBuriedPointServiceImpl implements UserBuriedPointService {

    @Resource
    LdBuryingPointMapper ldBuryingPointMapper;

    @Resource
    LdBuryingUserVideosMapper ldBuryingUserVideosMapper;

    @Resource
    LdBuryingPointAdditionalMapper ldBuryingPointAdditionalMapper;

    @Resource
    LdBuryingPointUserJpaRepository ldBuryingPointUserJpaRepository;

    @Resource
    SmallVideoService smallVideoService;


    private static final String APP_START = "App_start";

    @Override
    public void userBuriedPointEvery(LdBuryingPointEvery ldBuryingPointEvery) {
        ldBuryingPointEvery.setUserState(getUserState(ldBuryingPointEvery.getDeviceId(), ldBuryingPointEvery.getAppType()));

        LdBuryingPointEveryVo ldBuryingPointEveryVo = new LdBuryingPointEveryVo();
        //????????????
        BeanUtils.copyProperties(ldBuryingPointEvery,ldBuryingPointEveryVo);
        Map<String, Object> datas = new ConcurrentHashMap<>(100);
        String jsonStr = JSONObject.toJSONString(ldBuryingPointEveryVo);
        Map<String,Object> jsonMap = JSONObject.parseObject(jsonStr);
        jsonMap.keySet().forEach(e -> datas.put(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, e),jsonMap.get(e)));
        Date date = new Date();
        String dateToStr = DateUtil.parseDateToStr(date, "yyyyMMdd");
        datas.put("create_time", date);
        datas.put("create_date", DateUtil.parseDateToStr(date,"yyyy-MM-dd"));
        ldBuryingPointMapper.insertDynamic("ld_burying_point_every" + dateToStr,datas);
    }

    @Scheduled(cron = "0 0 16 * * ?")
    public void createTableQuartz() {
        RedisLock redisLock = new RedisLock(RedisKeyConstant.CREATE_TABLE_REDIS_LOCK, RedisKeyConstant.CREATE_TABLE_REDIS_LOCK_TIME);
        if (redisLock.lock()) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(new Date());
            c.add(Calendar.DAY_OF_MONTH, 1);
            Date nextDate = c.getTime();
            String dateToStr = DateUtil.parseDateToStr(nextDate, "yyyyMMdd");
            //???????????????
            ldBuryingPointMapper.createTableDynamic("ld_burying_point_every" + dateToStr);
            log.info("ld_burying_point_every" + dateToStr + "???????????????=======");
        }
    }


    @Override
    public void userBuriedPointSecondEdition(LdBuryingPoint ldBuryingPoint) {
        //??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        if (APP_START.equals(ldBuryingPoint.getActionId())) {
            LdBuryingPoint ldBuryingPointASC = ldBuryingPointMapper.selectByDeviceIdAndAppTypeOrderByCreateTimeAsc(ldBuryingPoint.getDeviceId(), ldBuryingPoint.getAppType());
            LdBuryingPoint ldBuryingPointDESC = ldBuryingPointMapper.selectByDeviceIdAndAppTypeOrderByCreateTimeDESC(ldBuryingPoint.getDeviceId(), ldBuryingPoint.getAppType());
            if (ldBuryingPointASC == null) {
                ldBuryingPoint.setOpenTime(new Date());
            } else {
                ldBuryingPoint.setOpenTime(ldBuryingPointASC.getOpenTime());
                ldBuryingPoint.setUpOpenTime(ldBuryingPointDESC.getCreateTime());
            }
            try {
                ldBuryingPointMapper.ldBuryingPoint(ldBuryingPoint);
            } catch (Exception e) {
                if (e.getCause() instanceof MySQLIntegrityConstraintViolationException) {
                    log.error("????????????????????????????????????????????????????????????");
                }
            }
        } else {
            LdBuryingPoint ldBuryingPointDESC = ldBuryingPointMapper.selectByDeviceIdAndAppTypeOrderByCreateTimeDESC(ldBuryingPoint.getDeviceId(), ldBuryingPoint.getAppType());
            if (ldBuryingPointDESC != null) {
                //????????????????????????????????????????????????????????????????????????????????????????????? ??????,??? ; sql???????????????????????????  ?????????????????????????????????????????????????????????SerialNumber
                if (StringUtils.isNotBlank(ldBuryingPointDESC.getSerialNumber())
                        && !ldBuryingPointDESC.getSerialNumber().contains(ldBuryingPoint.getSerialNumber())) {
                    ldBuryingPointDESC.setSerialNumber(ldBuryingPointDESC.getSerialNumber() + "," + ldBuryingPoint.getSerialNumber());
                }
                ldBuryingPointDESC.setActionId(ldBuryingPoint.getActionId());
                ldBuryingPointDESC.setAllPermission(ldBuryingPoint.getAllPermission());
                ldBuryingPointMapper.updateByIdAndTimeDESC(ldBuryingPointDESC);
            }
        }
    }

    @Override
    @Transactional
    public void ldBuryingPointAdditionalSecondEdition(LdBuryingPointAdditional ldBuryingPointAdditional) throws Exception {
        ldBuryingPointAdditional.setSubmitTime(new Date());
        //????????????????????????????????????????????????????????????????????????????????????????????????
        LdBuryingPointAdditional todayBuryingPoint = ldBuryingPointAdditionalMapper.findTodayBuryingPoint(ldBuryingPointAdditional);
        //?????????????????????????????????????????????????????? ?????????????????????
        if (todayBuryingPoint == null && LdBuryingPointAdditional.ACTIVATION_BURYING.equals(ldBuryingPointAdditional.getActionId())) {
            try {
                ldBuryingPointAdditional.setUserState(getUserState(ldBuryingPointAdditional.getDeviceId(), ldBuryingPointAdditional.getAppType()));
                ldBuryingPointAdditionalMapper.insertSelective(ldBuryingPointAdditional);
            } catch (Exception e) {
                if (e.getCause() instanceof MySQLIntegrityConstraintViolationException) {
                    log.error("????????????????????????????????????????????????????????????");
                }
            }
        } else {
            //?????????????????????????????????
            if (LdBuryingPointAdditional.SET_THE_CALL.equals(ldBuryingPointAdditional.getActionId())) {
                //??????
                LdBuryingUserVideos ldBuryingUserVideos = new LdBuryingUserVideos();
                ldBuryingUserVideos.setDeviceId(ldBuryingPointAdditional.getDeviceId());
                ldBuryingUserVideos.setOperationType(LdBuryingUserVideos.INCOMINGCALLVIDEO);
                ldBuryingUserVideos.setVideosId(ldBuryingPointAdditional.getVideosId());
                ldBuryingUserVideos.setAppType(ldBuryingPointAdditional.getAppType());
                ldBuryingUserVideos.setCreateDay(new Date());
                //????????????id  ??????id  ????????????  ????????????  ????????????????????????  ??????????????????????????????    ???????????????????????????????????????????????????????????????????????????????????????????????????
                //??????????????????  ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                //??????????????????????????????????????????
                LdBuryingUserVideos ldBuryingUserVideos1 = ldBuryingUserVideosMapper.selectByDeviceIdAndVideoIdAndOperationType(ldBuryingUserVideos);
                //??????????????????????????????????????????
                int ldBuryingUserCount = ldBuryingUserVideosMapper.selectCountByDeviceIdAndVideoIdAndOperationType(ldBuryingUserVideos);
                if (ldBuryingUserVideos1 == null && ldBuryingUserCount < 3) {
                    ldBuryingUserVideosMapper.insertSelective(ldBuryingUserVideos);
                    if (ldBuryingUserCount == 0) {
                        ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.SET_THE_CALL_1);
                        ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
                    } else if (ldBuryingUserCount == 1) {
                        ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.SET_THE_CALL_2);
                        ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
                    } else if (ldBuryingUserCount == 2) {
                        ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.SET_THE_CALL_3);
                        ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
                    }
                }
            } else if (LdBuryingPointAdditional.A_SMALL_VIDEO_INITIALIZATION.equals(ldBuryingPointAdditional.getActionId()) || LdBuryingPointAdditional.A_SMALL_VIDEO_5.equals(ldBuryingPointAdditional.getActionId())) {
                //????????????      1.7.0??????
                ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.A_SMALL_VIDEO_INITIALIZATION);
                //??????
                LdBuryingUserVideos ldBuryingUserVideos = new LdBuryingUserVideos();
                ldBuryingUserVideos.setDeviceId(ldBuryingPointAdditional.getDeviceId());
                ldBuryingUserVideos.setVideosId(ldBuryingPointAdditional.getVideosId());
                ldBuryingUserVideos.setAppType(ldBuryingPointAdditional.getAppType());
                ldBuryingUserVideos.setCreateDay(new Date());
                ldBuryingUserVideos.setOperationType(LdBuryingUserVideos.SMALLVIDEOS);
                //??????????????????????????????????????????
                LdBuryingUserVideos ldBuryingUserVideos1 = ldBuryingUserVideosMapper.selectByDeviceIdAndVideoIdAndOperationType(ldBuryingUserVideos);
                //??????????????????????????????
                int ldBuryingUserCount = ldBuryingUserVideosMapper.selectCountByDeviceIdAndVideoIdAndOperationType(ldBuryingUserVideos);
                //???????????????????????????????????? ??? ????????????????????????5
                if (ldBuryingUserVideos1 == null && ldBuryingUserCount < 5) {
                    ldBuryingUserVideosMapper.insertSelective(ldBuryingUserVideos);
                    if (ldBuryingUserCount == 0) {
                        ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.A_SMALL_VIDEO_1);
                        ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
                    } else if (ldBuryingUserCount == 1) {
                        ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.A_SMALL_VIDEO_2);
                        ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
                    } else if (ldBuryingUserCount == 4) {
                        ldBuryingPointAdditional.setActionId(LdBuryingPointAdditional.A_SMALL_VIDEO_5);
                        ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
                    }
                }
            } else {
                ldBuryingPointAdditionalMapper.updateLdBuryingUserVideosByActionId(ldBuryingPointAdditional);
            }
        }
        if (LdBuryingPointAdditional.A_SMALL_VIDEO_INITIALIZATION.equals(ldBuryingPointAdditional.getActionId())) {
            //????????????????????? add shixh1211
            if (ldBuryingPointAdditional.getVideosId() != null && ldBuryingPointAdditional.getVideosId() > 0) {
                Map<String, Object> params = new HashMap<String, Object>();
                params.put("id", ldBuryingPointAdditional.getVideosId());
                params.put("opType", "30");
                smallVideoService.updateVideosCount(params);
            }
        }
    }

    //????????????????????????
    public boolean isNewUser(LdBuryingPointUser ldBuryingPointUser) {
        if (ldBuryingPointUser == null) {
            return true;
        } else {
            Date date = DateUtil.getSpecifiedDay(new Date(), "yyyy-MM-dd");
            //?????????????????????????????????????????????????????????????????????
            if (DateUtil.getSpecifiedDay(ldBuryingPointUser.getCreateTime(), "yyyy-MM-dd").getTime() == date.getTime()) {
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean getUserState(String deviceId, String appType) {
        //??????AppType????????????id  ??????????????????????????????
        LdBuryingPointUser ldBuryingPointUser = ldBuryingPointUserJpaRepository.findUserBuryingPointIsNew(deviceId, appType);
        if (ldBuryingPointUser == null) {
            ldBuryingPointUserJpaRepository.saveldBuryingPointUser(deviceId, appType, new Date());
        }
        if (isNewUser(ldBuryingPointUser)) return true;
        return false;
    }

}
