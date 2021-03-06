package com.miguan.ballvideo.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.miguan.ballvideo.common.constants.Constant;
import com.miguan.ballvideo.common.constants.PushArticleConstant;
import com.miguan.ballvideo.common.enums.PushChannel;
import com.miguan.ballvideo.common.util.DateUtil;
import com.miguan.ballvideo.common.util.ResultMap;
import com.miguan.ballvideo.common.util.push.PushUtil;
import com.miguan.ballvideo.entity.PushArticle;
import com.miguan.ballvideo.entity.PushArticleConfig;
import com.miguan.ballvideo.entity.UserBuryingPointGarbage;
import com.miguan.ballvideo.mapper.PushResultCountMapper;
import com.miguan.ballvideo.rabbitMQ.listener.common.ProducerMqCallers;
import com.miguan.ballvideo.rabbitMQ.util.RabbitMQConstant;
import com.miguan.ballvideo.redis.util.RedisKeyConstant;
import com.miguan.ballvideo.repositories.BuryingPointGarbageRepository;
import com.miguan.ballvideo.service.*;
import com.miguan.ballvideo.vo.ClDeviceVo;
import com.miguan.ballvideo.vo.mongodb.AutoPushLog;
import com.miguan.ballvideo.vo.push.PushResultCountVo;
import com.miguan.message.push.utils.huawei.messaging.SendResponce;
import com.vivo.push.sdk.notofication.InvalidUser;
import com.vivo.push.sdk.notofication.Result;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.cgcg.redis.core.entity.RedisLock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PushSeviceImpl implements PushSevice {
    @Resource
    private PushArticleService pushArticleService;

    @Resource
    private PushArticleConfigService pushArticleConfigService;

    @Resource
    private ClDeviceService clDeviceService;

    @Resource
    private PushArticleMobileService pushArticleMobileService;

    @Resource
    private PushArticleSendResultService pushArticleSendResultService;

    @Resource
    private ClUserOpinionService clUserOpinionService;

    @Resource
    private RedisService redisService;

    @Resource
    private RedisDB6Service redisDB6Service;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private PushResultCountMapper pushResultCountMapper;

    @Resource(name = "xypushMongoTemplate")
    private MongoTemplate mongoTemplate;

    @Resource
    private BuryingPointGarbageRepository garbageRepository;

    @Resource
    private ProducerMqCallers producerMqCallers;

    @Override
    public ResultMap realTimeSendInfo(Long id, List<String> distinctIds) {
        try {
            PushArticle pushArticle = pushArticleService.findOneToPush(id);
            if (pushArticle == null || StringUtils.isBlank(pushArticle.getTitle())
                    || StringUtils.isBlank(pushArticle.getNoteContent())) {
                log.error("???????????????????????????????????????");
                return ResultMap.error("???????????????????????????????????????");
            }
            //??????distinctIds???????????????????????????????????????????????????????????????????????????????????????
            if(distinctIds == null){
                // ????????????????????????????????????????????? saveClUserOpinionPlus
                clUserOpinionService.saveClUserOpinionByPushArticle(pushArticle);
            }


            ResultMap resultMap;
            /* ????????????????????? IOS ??? appPackage ????????????????????? List????????????????????????????????? List ????????? */
            //if (Constant.IOSPACKAGE.equals(pushArticle.getAppPackage())) {
            if (Constant.IOSPACKAGELIST.contains(pushArticle.getAppPackage())) {
                resultMap = sendInfoToIOS(pushArticle,distinctIds);
            } else {
                resultMap = sendInfoToAndroid(pushArticle,distinctIds);
            }
            if (resultMap != null) {
                return resultMap;
            }
        } catch (Exception e) {
            log.error("?????????????????????======>" + e.getMessage());
            return ResultMap.error("?????????????????????????????????");
        }
        return ResultMap.success();
    }

    /**
     * ?????????????????????????????????
     */
    //@Scheduled(cron = "0 30 8 * * ?")
    private void taskPushInfo1() {
        pushInfo();
    }

    /**
     * ?????????????????????????????????
     */
    //@Scheduled(cron = "0 30 12 * * ?")
    private void taskPushInfo2() {
        pushInfo();
    }

    /**
     * ?????????????????????????????????
     */
    //@Scheduled(cron = "0 0 19 * * ?")
    private void taskPushInfo3() {
        pushInfo();
    }

    /**
     * ?????????????????????????????????
     */
    //@Scheduled(cron = "0 0 22 * * ?")
    private void taskPushInfo4() {
        pushInfo();
    }

    private void pushInfo() {
        RedisLock redisLock = new RedisLock(RedisKeyConstant.TASK_PUSH_CLOCK, RedisKeyConstant.defalut_seconds);
        if (redisLock.lock()) {
            log.info("???????????????????????????????????????----");
            String pushTime = DateUtil.parseDateToStr(new Date(), DateUtil.DATEFORMAT_STR_010);
            handTimeSendInfo(pushTime);
        }
    }

    private ResultMap handTimeSendInfo(String pushTime) {
        try {
            List<PushArticle> pushArticles = pushArticleService.findByType(5);
            if (CollectionUtils.isNotEmpty(pushArticles)) {
                for (PushArticle pushArticle : pushArticles) {
                    if (pushArticle == null || StringUtils.isBlank(pushArticle.getTitle())
                            || StringUtils.isBlank(pushArticle.getNoteContent())) {
                        log.error("???????????????????????????????????????");
                        return ResultMap.error("???????????????????????????????????????");
                    }
                    pushArticle.setPushTime(pushTime);
                    // ????????????????????????????????????????????? saveClUserOpinionPlus
                    clUserOpinionService.saveClUserOpinionByPushArticle(pushArticle);
                    ResultMap resultMap;
                    if (Constant.IOSPACKAGELIST.contains(pushArticle.getAppPackage())) {
                        resultMap = sendInfoToIOS(pushArticle,null);
                    } else {
                        resultMap = sendInfoToAndroid(pushArticle,null);
                    }
                    if (resultMap != null) {
                        return resultMap;
                    }
                }
            }
        } catch (Exception e) {
            return ResultMap.error("?????????????????????????????????");
        }
        return ResultMap.success();
    }

    @Override
    public ResultMap sendInfoToIOS(PushArticle pushArticle, List<String> distinctIds) {
        log.info("IOS???????????????------->" + pushArticle.toString());
        Map<String, String> param = PushUtil.getParaMap(pushArticle,0);
        Map<String, Object> pushParams = PushUtil.getExpireTime(pushArticle.getExpireTime());
        pushParams.put("pushArticle", pushArticle);
        if (consumeInterestCat(pushArticle, distinctIds)) {
            return null;
        }
        return youMengPush(pushParams, param, distinctIds);
    }

    @Override
    public ResultMap sendInfoToAndroid(PushArticle pushArticle, List<String> distinctIds) {
        log.info("Android???????????????------->id:{},pushArticle:{},distinctIds:{}" + pushArticle.getId(),JSON.toJSONString(pushArticle),JSON.toJSONString(distinctIds));
        Map<String, String> param = PushUtil.getParaMap(pushArticle,0);
        Map<String, Object> pushParams = PushUtil.getExpireTime(pushArticle.getExpireTime());
        pushParams.put("pushArticle", pushArticle);
        pushParams.put("packageName", "com.mg.xyvideo.module.main.MainActivity");
        Map<String, List<String>> tokensMap;
        if (PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType())) {
            log.info("########Android??????????????????########");
            tokensMap = PushUtil.getTokensMap(pushArticle);
            // ??????????????????
            puthAllPlatform(pushParams, param, tokensMap);
        } else {
            if (consumeInterestCat(pushArticle, distinctIds)) {
                return null;
            }
            //?????????????????? ??????????????????????????????token ????????????
            this.youMengPush(pushParams, param, distinctIds);

            // ????????????5000?????????token???????????????????????????
            Map<String, Object> userParam = new HashMap<>();
            userParam.put("appPackage", pushArticle.getAppPackage());
            userParam.put("distinctIds",distinctIds);
            log.info("########Android??????/??????????????????########");
            // ????????????
            int index = 0;
            while (true) {
                userParam.put("index", index);
                //??????????????????????????????????????????
                //??????????????????
                List<ClDeviceVo> clDeviceVos = getClDeviceVosByUserType(pushArticle, distinctIds, userParam);
                if(CollectionUtils.isEmpty(clDeviceVos)){
                    return null;
                }
                tokensMap = PushUtil.getTokensMapByDevice(clDeviceVos);
                log.info("????????????????????????/?????????????????????tokensMap???{}", JSON.toJSONString(tokensMap));
                // ??????????????????
                puthAllPlatform(pushParams, param, tokensMap);
                if (index >= clDeviceVos.size())break;
                index += 5000;
            }
        }
        return null;
    }

    @Override
    public ResultMap sendInfoToCleanPage(PushArticle pushArticle) {
        log.info("Android???????????????????????????------->" + pushArticle.toString());
        Map<String, String> param = PushUtil.getParaMap(pushArticle,0);
        Map<String, Object> pushParams = PushUtil.getExpireTime(pushArticle.getExpireTime());
        pushParams.put("pushArticle", pushArticle);
        pushParams.put("packageName", "com.mg.xyvideo.module.main.MainActivity");
        Map<String, List<String>> tokensMap;
        // ????????????5000?????????token???????????????????????????
        Map<String, Object> userParam = new HashMap<>();
        userParam.put("appPackage", pushArticle.getAppPackage());
        // ????????????????????????????????????
        Integer count = garbageRepository.findDeviceIdCount();
        // ????????????
        int index = 0;
        while (true) {
            List<UserBuryingPointGarbage> garbages = garbageRepository.findDeviceIdInfo(index);
            if (CollectionUtils.isEmpty(garbages)) {
                return null;
            }
            String ids = garbages.stream().map(d -> d.getDeviceId().toString()).collect(Collectors.joining(","));
            userParam.put("ids", ids);
            userParam.put("index", index);
            //??????????????????????????????????????????
            tokensMap = PushUtil.getTokensMapByDevice(clDeviceService.findAllTokens(userParam));
            // ??????????????????
            puthAllPlatform(pushParams, param, tokensMap);
            if (index >= count) {
                break;
            }
            index += 5000;
        }
        return null;
    }

    public void puthAllPlatform (Map<String, Object> pushParams, Map<String, String> param, Map<String, List<String>> tokensMap) {
        //??????????????????
        this.huaweiPush(pushParams, param, tokensMap);
        //vivo????????????
        this.vivoPush(pushParams, param, tokensMap);
        //oppo????????????
        this.oppoPush(pushParams, param, tokensMap);
        //??????????????????
        this.xiaomiPush(pushParams, param, tokensMap);
    }

    @Override
    public ResultMap realTimePushTest(Long id, String tokens, String pushChannel,List<String> distinctIds) {
        PushArticle pushArticle = pushArticleService.findOneToPush(id);
        if (pushArticle == null || StringUtils.isBlank(pushArticle.getTitle())
                || StringUtils.isBlank(pushArticle.getNoteContent())) {
            return ResultMap.error("???????????????????????????????????????");
        }
        Map<String, String> param = PushUtil.getParaMap(pushArticle,0);
        //?????????????????????tokens,tokens???????????????tokens???????????????????????????
        Map<String, List<String>> tokensMap = new HashMap<>();
        if (StringUtils.isNotEmpty(tokens)) {
            tokensMap.put(pushChannel, Arrays.asList(tokens.split(",")));
        } else {
            if (PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType())) {
                tokensMap = PushUtil.getTokensMap(pushArticle);
            } else {
                List<ClDeviceVo> clDeviceVos = Lists.newArrayList();
                Map<String, Object> userParam = new HashMap<>();
                userParam.put("appPackage", pushArticle.getAppPackage());
                if(PushArticleConstant.USER_TYPE_INTEREST_CAT.equals(pushArticle.getUserType())){
                    //??????????????????
                    userParam.put("distinctIds",distinctIds);
                    clDeviceVos = clDeviceService.findAllTokensByDistinct(userParam);
                }else{
                    //????????????
                    clDeviceVos = clDeviceService.findAllTokens(userParam);
                }
                tokensMap = PushUtil.getTokensMapByDevice(clDeviceVos);
            }
        }
        PushChannel channel = PushChannel.val(pushChannel);
        Map<String, Object> pushParams = PushUtil.getExpireTime(pushArticle.getExpireTime());
        pushParams.put("pushArticle", pushArticle);
        pushParams.put("packageName", "com.mg.xyvideo.module.main.MainActivity");
        //??????????????????????????????????????????
        switch (channel) {
            case YouMeng:
                return this.youMengPush(pushParams, param, distinctIds);//youMeng????????????
            case OPPO:
                return this.oppoPush(pushParams, param, tokensMap);//oppo????????????
            case XiaoMi:
                return this.xiaomiPush(pushParams, param, tokensMap);//??????????????????
            case VIVO:
                return this.vivoPush(pushParams, param, tokensMap);//vivo??????????????????
            case HuaWei:
                return this.huaweiPush(pushParams, param, tokensMap);//??????????????????
            default:
                return ResultMap.error("channel=" + channel + "??????????????????");
        }
    }

    //????????????
    private ResultMap youMengPush(Map<String, Object> pushParams, Map<String, String> param, List<String> distinctIds) {
        log.info("########youMeng??????????????????########");
        PushArticle pushArticle = (PushArticle) pushParams.get("pushArticle");
        if (PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType()) && StringUtils.isBlank(pushArticle.getDeviceTokens())) {
            return null;
        }
        String mobileType = Constant.ANDROID;

        if (Constant.IOSPACKAGELIST.contains(pushArticle.getAppPackage())) {
            mobileType = Constant.IOS;
        }
        PushArticleConfig youMeng = pushArticleConfigService.findPushArticleConfig(PushChannel.YouMeng.name(), mobileType, pushArticle.getAppPackage());
        log.info("?????????????????????????????????{}",JSON.toJSONString(youMeng));
        if (youMeng == null) return ResultMap.error("??????????????????????????????");

        pushParams.put(PushChannel.YouMeng.name(), youMeng);

        List<Map> resultList = Lists.newArrayList();
        try {
            //??????deviceTokens
            String deviceTokens = null;
            if(PushArticleConstant.USER_TYPE_INTEREST_CAT.equals(pushArticle.getUserType())){
                // ????????????5000?????????token???????????????????????????
                Map<String, Object> userParam = new HashMap<>();
                userParam.put("appPackage", pushArticle.getAppPackage());
                userParam.put("distinctIds",distinctIds);
                log.info("???????????????????????????????????????userParam???{}",JSON.toJSONString(userParam));
                // ????????????
                int index = 0;
                while (true) {
                    userParam.put("index", index);
                    //??????????????????
                    List<ClDeviceVo> clDeviceVos = getClDeviceVosByUserType(pushArticle,distinctIds,userParam);
                    log.info("??????????????????????????????clDeviceVos???{}",JSON.toJSONString(clDeviceVos));
                    if(CollectionUtils.isEmpty(clDeviceVos)){
                        break;
                    }
                    deviceTokens = clDeviceVos.stream().filter((clDevice -> StringUtils.isNotEmpty(clDevice.getDeviceToken())))
                            .map(clDeviceVo -> clDeviceVo.getDeviceToken()).collect(Collectors.joining(","));
                    log.info("??????????????????????????????deviceTokens???{}", deviceTokens);
                    if(StringUtils.isNotEmpty(deviceTokens)){
                        if (Constant.IOSPACKAGELIST.contains(pushArticle.getAppPackage())) {
                            resultList = pushArticleMobileService.youMengIosPushInfo(pushParams, param, deviceTokens);
                        } else {
                            resultList = pushArticleMobileService.youMengAndroidPushInfo(pushParams, param, deviceTokens);
                        }
                    }
                    if (index >= clDeviceVos.size())break;
                    index += 5000;
                }

            }else{
                if(PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType())) {
                    //??????????????????
                    deviceTokens = pushArticle.getDeviceTokens();
                }
                log.info("????????????????????????/?????????????????????deviceTokens???{}",deviceTokens);

                if (Constant.IOSPACKAGELIST.contains(pushArticle.getAppPackage())) {
                    resultList = pushArticleMobileService.youMengIosPushInfo(pushParams, param, deviceTokens);
                } else {
                    resultList = pushArticleMobileService.youMengAndroidPushInfo(pushParams, param, deviceTokens);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // todo  ??????????????????????????????
        log.info("?????????????????????????????????{}",JSON.toJSONString(resultList));
        if (CollectionUtils.isEmpty(resultList)) {
            saveSendResultInfo(pushArticle, null, PushChannel.YouMeng.name(),"0");
            return ResultMap.error("?????????????????????");
        }
        for (Map mapData : resultList) {
            String taskId = "";
            if (mapData.get("task_id") != null) {
                taskId = mapData.get("task_id").toString();
            }
            if (PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType())) {
                if (mapData.get("msg_id") != null) {
                    taskId = mapData.get("msg_id").toString();
                }
            }
            saveSendResultInfo(pushArticle, taskId, PushChannel.YouMeng.name(),"1");
        }
        return null;
    }

    //????????????
    private ResultMap huaweiPush(Map<String, Object> pushParams, Map<String, String> param,
                                 Map<String, List<String>> tokensMap) {
        log.info("########HuaWei??????????????????########");
        PushArticle pushArticle = (PushArticle) pushParams.get("pushArticle");
        PushArticleConfig huaWei = pushArticleConfigService.findPushArticleConfig(PushChannel.HuaWei.name(), Constant.ANDROID, pushArticle.getAppPackage());
        if (huaWei == null) {
            log.error("??????????????????????????????");
            return ResultMap.error("??????????????????????????????");
        }
        pushParams.put(PushChannel.HuaWei.name(), huaWei);
        List<String> list = tokensMap.get(PushChannel.HuaWei.name());
        if (CollectionUtils.isEmpty(list)) {
            log.error("huaWeiTokens??????????????????????????????");
            return null;
        }
        List<SendResponce> sendResponceList = pushArticleMobileService.huaweiPushInfo(pushParams, param, list);
        if (CollectionUtils.isEmpty(sendResponceList)) {
            saveSendResultInfo(pushArticle, null, PushChannel.HuaWei.name(), "0");
            log.error("?????????????????????");
            return ResultMap.error("?????????????????????");
        }
        setPushResultCountInfo(pushArticle.getId(), PushChannel.HuaWei.getCode(), list.size(), pushArticle.getAppPackage());
        for (SendResponce sendResponce : sendResponceList) {
            if (sendResponce != null) {
                saveSendResultInfo(pushArticle, sendResponce.getRequestId(), PushChannel.HuaWei.name(), "1");
            }
        }
        return null;
    }

    //vivo??????
    private ResultMap vivoPush(Map<String, Object> pushParams, Map<String, String> param,
                               Map<String, List<String>> tokensMap) {
        log.info("########vivo????????????########");
        PushArticle pushArticle = (PushArticle) pushParams.get("pushArticle");
        if (PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType()) && StringUtils.isBlank(pushArticle.getDeviceTokens())) {
            return null;
        }
        PushArticleConfig vivo = pushArticleConfigService.findPushArticleConfig(PushChannel.VIVO.name(), Constant.ANDROID, pushArticle.getAppPackage());
        if (vivo == null) {
            log.error("vivo????????????????????????");
            return ResultMap.error("vivo????????????????????????");
        }
        String limitVal = redisService.get(RedisKeyConstant.VIVO_LIMIT_TOKEN + vivo.getAppId(),String.class);
        if(limitVal != null){
            log.error("vivo??????????????????,???????????????");
            return null;
        }

        pushParams.put(PushChannel.VIVO.name(), vivo);
        List<Result> resultList = new ArrayList<>();

        List<String> list = tokensMap.get(PushChannel.VIVO.name());
        if (CollectionUtils.isEmpty(list)) {
            log.error("vivoTokens??????????????????????????????");
            return null;
        }
        setPushResultCountInfo(pushArticle.getId(), PushChannel.VIVO.getCode(), list.size(), pushArticle.getAppPackage());
        if (PushArticleConstant.USER_TYPE_ASSIGN_USER.equals(pushArticle.getUserType()) || list.size() < 2) {
            resultList = pushArticleMobileService.vivoPushByRegIds(pushParams, param, list);
        } else {
            resultList = pushArticleMobileService.vivoPushAll(pushParams, param, list);
        }
        if (CollectionUtils.isEmpty(resultList)) {
            saveSendResultInfo(pushArticle, null, PushChannel.VIVO.name(),"0");
            log.error("vivo????????????");
            return ResultMap.error("vivo????????????");
        }
        for (Result result : resultList) {
            if (result != null && result.getResult() == 0) {
                saveSendResultInfo(pushArticle, result.getTaskId(), PushChannel.VIVO.name(),"1");
                List<InvalidUser> invalidUsers = result.getInvalidUsers();
                if (CollectionUtils.isNotEmpty(invalidUsers)) {
                    String dataStr = JSON.toJSONString(invalidUsers);
                    rabbitTemplate.convertAndSend(RabbitMQConstant.PUSH_VIVO_ERROR_EXCHANGE, RabbitMQConstant.PUSH_VIVO_ERROR_KEY, dataStr);
                }
            }
        }
        return null;
    }

    @Override
    public void vivoInvalidUserSave(List<InvalidUser> invalidUsers) {
        for (InvalidUser invalidUser : invalidUsers) {
            if (invalidUser.getStatus() == 1 || invalidUser.getStatus() == 2) {
                String key = "pushVivoToken:ballvideo";
                redisDB6Service.lpush(key, invalidUser.getUserid());
            }
        }
    }

    //??????????????????????????????
    private void setPushResultCountInfo(Long id, String pushChannel, Integer num, String appPackage) {
        PushResultCountVo countVo = new PushResultCountVo();
        countVo.setPushArticleId(id);
        countVo.setPushChannel(pushChannel);
        countVo.setSendNum(num);
        countVo.setAppPackage(appPackage);
        String dataStr = JSON.toJSONString(countVo);
        rabbitTemplate.convertAndSend(RabbitMQConstant.PUSH_COUNT_INFO_EXCHANGE, RabbitMQConstant.PUSH_COUNT_INFO_KEY, dataStr);
    }

    @Override
    public void savePushCountInfo(PushResultCountVo countVo) {
        PushResultCountVo queryVo = pushResultCountMapper.getPushResultCountInfo(countVo);
        if (queryVo == null) {
            pushResultCountMapper.savePushResultCountInfo(countVo);
        } else {
            pushResultCountMapper.updatePushResultCountInfo(countVo);
        }
    }

    //oppo??????
    private ResultMap oppoPush(Map<String, Object> pushParams, Map<String, String> param,
                               Map<String, List<String>> tokensMap) {
        log.info("########oppo????????????########");
        List<String> list = tokensMap.get(PushChannel.OPPO.name());
        if (CollectionUtils.isEmpty(list)) {
            log.error("oppoTokens??????????????????????????????");
            return null;
        }
        PushArticle pushArticle = (PushArticle) pushParams.get("pushArticle");
        PushArticleConfig oppo = pushArticleConfigService.findPushArticleConfig(PushChannel.OPPO.name(), Constant.ANDROID, pushArticle.getAppPackage());
        if (oppo == null) {
            log.error("oppo????????????????????????");
            return ResultMap.error("oppo????????????????????????");
        }
        pushParams.put(PushChannel.OPPO.name(), oppo);
        List<com.oppo.push.server.Result> resultList = pushArticleMobileService.oppoPushByRegIds(pushParams, param, list);
        if (CollectionUtils.isEmpty(resultList)) {
            saveSendResultInfo(pushArticle, null, PushChannel.OPPO.name(),"0");
            log.error("OPPO????????????");
            return ResultMap.error("OPPO????????????");
        }
        setPushResultCountInfo(pushArticle.getId(), PushChannel.OPPO.getCode(), list.size(), pushArticle.getAppPackage());
        for (com.oppo.push.server.Result result : resultList) {
            if (result != null) {
                String taskId = result.getTaskId() == null ? result.getMessageId() : result.getTaskId();
                saveSendResultInfo(pushArticle, taskId, PushChannel.OPPO.name(),"1");
            }
        }
        return null;
    }

    //????????????
    private ResultMap xiaomiPush(Map<String, Object> pushParams, Map<String, String> param,
                                 Map<String, List<String>> tokensMap) {
        log.info("########??????????????????########");
        List<String> list = tokensMap.get(PushChannel.XiaoMi.name());
        if (CollectionUtils.isEmpty(list)) {
            log.info("xiaomiTokens??????????????????????????????");
            return null;
        }
        PushArticle pushArticle = (PushArticle) pushParams.get("pushArticle");
        PushArticleConfig xiaomi = pushArticleConfigService.findPushArticleConfig(PushChannel.XiaoMi.name(), Constant.ANDROID, pushArticle.getAppPackage());
        if (xiaomi == null) {
            log.error("xiaomi????????????????????????");
            return ResultMap.error("xiaomi????????????????????????");
        }
        pushParams.put(PushChannel.XiaoMi.name(), xiaomi);
        List<com.xiaomi.xmpush.server.Result> resultList = pushArticleMobileService.xiaomiPushInfo(pushParams, param, list);
        if (CollectionUtils.isEmpty(resultList)) {
            saveSendResultInfo(pushArticle, null, PushChannel.XiaoMi.name(),"0");
            log.error("?????????????????????");
            return ResultMap.error("??????????????????");
        }
        setPushResultCountInfo(pushArticle.getId(), PushChannel.XiaoMi.getCode(), list.size(), pushArticle.getAppPackage());
        for (com.xiaomi.xmpush.server.Result result : resultList) {
            if (result != null) {
                saveSendResultInfo(pushArticle, result.getMessageId(), PushChannel.XiaoMi.name(),"1");
            }
        }
        return null;
    }

    /**
     * ????????????????????????
     *
     * @param pushArticle
     * @param businessId
     * @param pushChannel
     */
    private void saveSendResultInfo(PushArticle pushArticle, String businessId, String pushChannel, String isSucc) {

        //?????????mongo??????
        List<AutoPushLog> autoPushLogs = Lists.newArrayList();
        try {
            AutoPushLog autoPushLog = new AutoPushLog();
            autoPushLog.setPush_id(pushArticle.getId());
            autoPushLog.setApp_package(pushArticle.getAppPackage());
            autoPushLog.setTitle(pushArticle.getTitle());
            autoPushLog.setType("0");
            autoPushLog.setContent(JSON.toJSONString(pushArticle));
            autoPushLog.setPush_channel(pushChannel);
            autoPushLog.setIs_succ(isSucc);
            autoPushLogs.add(autoPushLog);
            saveAutoPushInfoToMongo(autoPushLogs);
        } catch (Exception e) {
            log.error("????????????????????????monggo??????,???????????????" + JSON.toJSONString(autoPushLogs) +  ";??????:" + e.getMessage(), e);
        }


        if(Integer.parseInt(isSucc) == 1){
            try {
                pushArticleSendResultService.saveSendResult(pushArticle.getId(), pushChannel, businessId, pushArticle.getAppPackage());
            } catch (Exception e) {
                log.error("??????????????????:" + JSON.toJSONString(pushArticle));
                log.error("??????????????????:" + "businessId=" + businessId + ",pushChannel=" + pushChannel);
                log.error("?????????????????????:" + e.getMessage(), e);
            }
        }

    }
    private void saveAutoPushInfoToMongo(List<AutoPushLog> autoPushLogs) {
        mongoTemplate.insert(autoPushLogs,"auto_push_log");
    }

    public void executeInterestCat(String params){
        try {
            List<String> distinictIds = Lists.newArrayList();
            //params = "{\"pushId\":\"4725\",\"distinictIds\":[\"5472f98a5e701f87cf47a7c2b178b97b\",\"02e6e14adf564e524d0c18d4dd4bbac5\",\"69a82289d2e65447d3fdea258081b4aa\",\"54b459b4ed78518bf4dc969e97437142\"]}";
            JSONObject jsonObject = JSON.parseObject(params);
            String pushId = (String) jsonObject.get("pushId");
            JSONArray distinictIdsArr = (JSONArray) jsonObject.get("distinictIds");
            if(CollectionUtils.isNotEmpty(distinictIdsArr)){
                distinictIds = distinictIdsArr.stream().map(distinictId -> ""+distinictId).collect(Collectors.toList());
            }
            realTimeSendInfo(Long.parseLong(pushId),distinictIds);
            log.info("=====??????????????????????????????????????????=====");
        } catch (NumberFormatException e) {
            e.printStackTrace();
            log.error("??????????????????????????????????????????:" + e.getMessage(), e);
        }
    }

    /**
     * ???device??????????????????
     * @param pushArticle
     * @param distinctIds
     * @param userParam
     * @return
     */
    public List<ClDeviceVo> getClDeviceVosByUserType(PushArticle pushArticle, List<String> distinctIds, Map<String, Object> userParam) {
        List<ClDeviceVo> clDeviceVos = Lists.newArrayList();
        if(PushArticleConstant.USER_TYPE_INTEREST_CAT.equals(pushArticle.getUserType())){

            userParam.put("distinctIds", distinctIds);
            int count = clDeviceService.getAllTokensCountByDistinct(userParam);
            if(count > 0){
                clDeviceVos = clDeviceService.findAllTokensByDistinct(userParam);
            }
            log.info("########Android??????????????????MQ-Consumer########???userParam???{},count:{}",JSON.toJSONString(userParam),count);
        }else{
            //????????????
            int count = clDeviceService.getAllTokensCount(pushArticle.getAppPackage());
            if(count > 0){
                clDeviceVos = clDeviceService.findAllTokens(userParam);
            }
            log.info("########Android??????????????????########???userParam???{},count:{}",JSON.toJSONString(userParam),count);
        }
        return clDeviceVos;
    }


    /**
     * ??????????????????MQ
     * @param pushArticle
     * @param distinctIds
     * @return
     */
    private boolean consumeInterestCat(PushArticle pushArticle, List<String> distinctIds) {
        if(PushArticleConstant.USER_TYPE_INTEREST_CAT.equals(pushArticle.getUserType())){
            //??????distinctIds???????????????????????????MQ
            if(distinctIds == null){
                log.error("???MQ-Producer???Android??????/?????????????????? consumeInterestCat into");
                if (StringUtils.isEmpty(pushArticle.getCatId())){
                    log.error("???MQ-Producer???Android??????????????????,catIds?????????pushId={}", pushArticle.getId());
                    return true;
                }
                Map<String,String> pushMap = Maps.newHashMap();
                pushMap.put("pushId","" + pushArticle.getId());
                pushMap.put("catIds", pushArticle.getCatId());
                if(pushArticle.getType() == 3 || pushArticle.getType() == 4){
                    pushMap.put("videoId", pushArticle.getTypeValue());
                }
                pushMap.put("appPackage",pushArticle.getAppPackage());
                producerMqCallers.producerMqCallers(RabbitMQConstant.INTEREST_CAT,JSON.toJSONString(pushMap));
                log.error("???MQ-Producer?????????????????????,{}",JSON.toJSONString(pushMap));
                return true;
            }
        }
        return false;
    }
}
