package com.miguan.idmapping.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.cgcg.base.core.exception.CommonException;
import com.miguan.idmapping.common.constants.BuryingActionType;
import com.miguan.idmapping.common.constants.RabbitMQConstant;
import com.miguan.idmapping.common.constants.RedisKeyConstant;
import com.miguan.idmapping.common.enums.UserFromEnums;
import com.miguan.idmapping.common.utils.DateUtil;
import com.miguan.idmapping.common.utils.Global;
import com.miguan.idmapping.common.utils.IPUtil;
import com.miguan.idmapping.entity.ClUserComment;
import com.miguan.idmapping.entity.ClUserVideos;
import com.miguan.idmapping.entity.CommentReply;
import com.miguan.idmapping.mapper.ClUserCommentMapper;
import com.miguan.idmapping.mapper.ClUserMapper;
import com.miguan.idmapping.mapper.ClUserVideosMapper;
import com.miguan.idmapping.mapper.CommentReplyMapper;
import com.miguan.idmapping.service.ClSmsService;
import com.miguan.idmapping.service.ClUserService;
import com.miguan.idmapping.service.IUserGoldService;
import com.miguan.idmapping.service.IXyBuryingPointUserService;
import com.miguan.idmapping.service.RedisService;
import com.miguan.idmapping.vo.ClUserVo;
import com.miguan.idmapping.vo.UserRefInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Base64Utils;
import tool.util.StringUtil;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.miguan.idmapping.common.utils.DbCollectionUtils.getDeviceMongoCollection;
import static com.miguan.idmapping.common.utils.DbCollectionUtils.getMongoCollection;

/**
 * ?????????ServiceImpl
 *
 * @author xy.chen
 * @date 2019-08-09
 **/
@Slf4j
@Service
public class ClUserServiceImpl implements ClUserService {

    @Resource
    private ClUserMapper clUserMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    ClUserCommentMapper clUserCommentMapper;
    @Resource
    ClUserVideosMapper clUserVideosMapper;
    @Resource
    CommentReplyMapper commentReplyMapper;

    @Autowired
    private RedisService redisService;
    @Autowired
    private ClSmsService clSmsService;
    @Autowired
    private IXyBuryingPointUserService xyBuryingPointUserService;
    @Autowired
    private IUserGoldService userGoldService;

    @Resource
    private MongoTemplate mongoTemplate;
    @Autowired
    private UserUUIDServiceImpl userUUIDService;
    /**
     * ????????????
     *
     *
     * @param publicInfo
     * @param commonAttr
     * @param request
     * @param clUser ????????????
     * @param vcode    ???????????????
     * @return
     */
    @Override
    public Map<String, Object> login(String publicInfo, String commonAttr, HttpServletRequest request, ClUserVo clUser, String vcode) {
        UserFromEnums typeEnum = UserFromEnums.toEnums(clUser.getType());
        if (typeEnum == null) {
            throw new NullPointerException("???????????????????????????type?????????android,ios,h5,weixin,xiaochengxu,web");
        }
        Map<String, Object> result = new HashMap<String, Object>();
        String loginName = clUser.getLoginName();
        int results = clSmsService.verifySms(loginName, "login", vcode);
        String vmsg = null;
        if (results == 1) {
            vmsg = null;
        } else if (results == -1) {
            vmsg = "??????????????????";
        } else {
            vmsg = "???????????????";
        }
        if (vmsg != null) {
            result.put("success", "-1");
            result.put("msg", "?????????????????????" + vmsg);
            return result;
        }

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("loginName", loginName);
        List<ClUserVo> clUserList = clUserMapper.findClUserList(paramMap);
        String uuid = clUser.getUuid();
        if (StringUtils.isBlank(uuid)) {
            throw new NullPointerException("?????????UUID???????????????");
        }
        boolean isRegister = false;
        if (clUserList == null || clUserList.size() == 0) {
            //?????????????????????
            updateUserDevice(uuid, typeEnum, clUser);
            //?????????????????????????????????
            result.put("register", "1");//????????????
            String suffixName = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8);
            clUser.setName("xy_" + suffixName);
            String appEnvironment = Global.getValue("app_environment");
            if ("prod".equals(appEnvironment)) {
                clUser.setImgUrl(Global.getValue("prod_default_head_img"));//??????????????????
            } else {
                clUser.setImgUrl(Global.getValue("dev_default_head_img"));//??????????????????
            }
            clUser.setState("10");//
            clUser.setAppPackage(clUser.getAppPackage());
            clUserMapper.saveClUser(clUser);
            result.put("uuid", uuid);
            //????????????id
            clUserList = clUserMapper.findClUserList(paramMap);
            isRegister = true;
        } else {
            //?????????????????????
            if ("20".equals(clUserList.get(0).getState())) {
                result.put("success", "-1");
                result.put("msg", "??????????????????");
                return result;
            }
            //??????????????????
            clUser.setAppPackage(clUser.getAppPackage());
            ClUserVo usered = clUserList.get(0);
            //???????????????????????????uuid??????????????????????????????UUID
            if (StringUtils.isBlank(usered.getUuid())) {
                updateUserDevice(uuid, typeEnum, clUser);
            } else {
                clUser.setUuid(usered.getUuid());
            }
            clUserMapper.updateClUser(clUser);
            result.put("uuid", clUser.getUuid());
        }
        //?????????????????????????????????
        if (!userGoldService.initUserGold(result, clUser, clUserList,"2")) {
            return result;
        }
        clUser = clUserList.get(0);
        String token = UUID.randomUUID().toString().replaceAll("-", "");
        result.put("token", token);
        result.put("userId", String.valueOf(clUserList.get(0).getId()));
        redisService.set(RedisKeyConstant.USER_TOKEN + clUserList.get(0).getId(), token, RedisKeyConstant.USER_TOKEN_SECONDS);  //token????????????30???

        result.put("success", "0");
        result.put("msg", "????????????");
        result.put("userInfo", clUserList.get(0));
        try {
            String userIp = IPUtil.getIpAddr(request);
            //?????????????????? ??????????????????????????? ??? APP?????? ?????????User?????????
            clUserList.get(0).setAppVersion(clUser.getAppVersion());
            clUserList.get(0).setOsVersion(clUser.getOsVersion());
            clUserList.get(0).setSystemVersion(clUser.getSystemVersion());
            clUserList.get(0).setChannelId(clUser.getChannelId());
            clUserList.get(0).setImei(clUser.getImei());
            clUserList.get(0).setAppPackage(clUser.getAppPackage());
            if (isRegister) {
                sendToMQ(publicInfo, commonAttr, userIp, clUserList.get(0), BuryingActionType.XY_REGISTER,"2");
            }
            sendToMQ(publicInfo, commonAttr, userIp, clUserList.get(0), BuryingActionType.XY_LOGIN,"2");
        } catch (Exception e) {
            log.error("?????????????????????????????????{}", e);
        }
        return result;
    }

    /**
     * ????????????
     *
     * @param clUserVo
     * @param nickName
     * @param headPic
     * @return
     */
    @Override
    public Map<String, Object> wecharLogin(HttpServletRequest request,String publicInfo,String commonAttr,ClUserVo clUserVo, String nickName, String headPic) {
        UserFromEnums typeEnum = UserFromEnums.toEnums(clUserVo.getType());
        if (typeEnum == null) {
            throw new NullPointerException("???????????????????????????type?????????android,ios,h5,weixin,xiaochengxu,web");
        }
        Map<String, Object> result = new HashMap<String, Object>();
        String openId = clUserVo.getOpenId();
        if(StringUtil.isEmpty(openId)){
            throw new CommonException("openId????????????");
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("openId", openId);
        List<ClUserVo> clUserList = clUserMapper.findClUserList(paramMap);
        boolean isRegister = false;
        String uuid = clUserVo.getUuid();
        if (StringUtils.isBlank(uuid)) {
            throw new NullPointerException("?????????UUID???????????????");
        }
        if(clUserList != null && clUserList.size()>0){//??????????????????
            //?????????????????????
            if ("20".equals(clUserList.get(0).getState())) {
                result.put("success", "-1");
                result.put("msg", "??????????????????");
                return result;
            }
            clUserVo.setName(nickName);
            clUserVo.setImgUrl(headPic);
            ClUserVo usered = clUserList.get(0);
            //???????????????????????????uuid??????????????????????????????UUID
            if (StringUtils.isBlank(usered.getUuid())) {
                updateUserDevice(uuid, typeEnum, clUserVo);
            } else {
                clUserVo.setUuid(usered.getUuid());
            }
            clUserMapper.updateClUser(clUserVo);
        }else{//????????????
            //?????????????????????
            updateUserDevice(uuid, typeEnum, clUserVo);
            result.put("register", "1");//????????????
            clUserVo.setState("10");
            clUserVo.setName(nickName);
            clUserVo.setImgUrl(headPic);
            clUserMapper.saveClUser(clUserVo);
            isRegister = true;
            //????????????id
            clUserList = clUserMapper.findClUserList(paramMap);
        }

        //?????????????????????????????????
        if (!userGoldService.initUserGold(result, clUserVo, clUserList,"1")){
            return result;
        }
        clUserVo = clUserList.get(0);
        String token = UUID.randomUUID().toString().replaceAll("-", "");
        result.put("token", token);
        result.put("userId", String.valueOf(clUserList.get(0).getId()));
        redisService.set(RedisKeyConstant.USER_TOKEN + clUserList.get(0).getId(), token, RedisKeyConstant.USER_TOKEN_SECONDS);  //token????????????30???

        result.put("success", "0");
        result.put("msg", "????????????");
        result.put("userInfo", clUserList.get(0));
        try {
            String userIp = IPUtil.getIpAddr(request);
            //?????????????????? ??????????????????????????? ??? APP?????? ?????????User?????????
            clUserList.get(0).setAppVersion(clUserVo.getAppVersion());
            clUserList.get(0).setOsVersion(clUserVo.getOsVersion());
            clUserList.get(0).setSystemVersion(clUserVo.getSystemVersion());
            clUserList.get(0).setChannelId(clUserVo.getChannelId());
            clUserList.get(0).setImei(clUserVo.getImei());
            clUserList.get(0).setAppPackage(clUserVo.getAppPackage());
            if (isRegister) {
                sendToMQ(publicInfo, commonAttr, userIp, clUserList.get(0), BuryingActionType.XY_REGISTER,"1");
            }
            sendToMQ(publicInfo, commonAttr, userIp, clUserList.get(0), BuryingActionType.XY_LOGIN,"1");
        } catch (Exception e) {
            log.error("?????????????????????????????????{}", e);
        }
        return result;
    }

    @Override
    public Map<String, Object> appleLogin(HttpServletRequest request,String publicInfo,String commonAttr,ClUserVo clUserVo) {
        UserFromEnums typeEnum = UserFromEnums.toEnums(clUserVo.getType());
        if (typeEnum == null) {
            throw new NullPointerException("???????????????????????????type?????????android,ios,h5,weixin,xiaochengxu,web");
        }
        Map<String, Object> result = new HashMap<String, Object>();
        String appleId = clUserVo.getAppleId();
        if(StringUtil.isEmpty(appleId)){
            throw new CommonException("appleId????????????");
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("appleId", appleId);
        List<ClUserVo> clUserList = clUserMapper.findClUserList(paramMap);
        boolean isRegister = false;
        String uuid = clUserVo.getUuid();
        if (StringUtils.isBlank(uuid)) {
            throw new NullPointerException("?????????UUID???????????????");
        }
        if(clUserList != null && clUserList.size()>0){//??????????????????
            //?????????????????????
            if ("20".equals(clUserList.get(0).getState())) {
                result.put("success", "-1");
                result.put("msg", "??????????????????");
                return result;
            }
            ClUserVo usered = clUserList.get(0);
            //???????????????????????????uuid??????????????????????????????UUID
            if (StringUtils.isBlank(usered.getUuid())) {
                updateUserDevice(uuid, typeEnum, clUserVo);
            } else {
                clUserVo.setUuid(usered.getUuid());
            }
            clUserMapper.updateClUser(clUserVo);
        }else{//????????????
            //?????????????????????
            updateUserDevice(uuid, typeEnum, clUserVo);
            if(StringUtils.isEmpty(clUserVo.getName())){
                String suffixName = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 8);
                clUserVo.setName("xy_" + suffixName);
            }
            result.put("register", "1");//????????????
            clUserVo.setState("10");
            String appEnvironment = Global.getValue("app_environment");
            if ("prod".equals(appEnvironment)) {
                clUserVo.setImgUrl(Global.getValue("prod_default_head_img"));//??????????????????
            } else {
                clUserVo.setImgUrl(Global.getValue("dev_default_head_img"));//??????????????????
            }
            clUserMapper.saveClUser(clUserVo);
            isRegister = true;
            //????????????id
            clUserList = clUserMapper.findClUserList(paramMap);
        }

        //?????????????????????????????????
        if (!userGoldService.initUserGold(result, clUserVo, clUserList,"3")){
            return result;
        }
        clUserVo = clUserList.get(0);
        String token = UUID.randomUUID().toString().replaceAll("-", "");
        result.put("token", token);
        result.put("userId", String.valueOf(clUserList.get(0).getId()));
        redisService.set(RedisKeyConstant.USER_TOKEN + clUserList.get(0).getId(), token, RedisKeyConstant.USER_TOKEN_SECONDS);  //token????????????30???

        result.put("success", "0");
        result.put("msg", "????????????");
        result.put("userInfo", clUserList.get(0));
        try {
            String userIp = IPUtil.getIpAddr(request);
            //?????????????????? ??????????????????????????? ??? APP?????? ?????????User?????????
            clUserList.get(0).setAppVersion(clUserVo.getAppVersion());
            clUserList.get(0).setOsVersion(clUserVo.getOsVersion());
            clUserList.get(0).setSystemVersion(clUserVo.getSystemVersion());
            clUserList.get(0).setChannelId(clUserVo.getChannelId());
            clUserList.get(0).setImei(clUserVo.getImei());
            clUserList.get(0).setAppPackage(clUserVo.getAppPackage());
            if (isRegister) {
                sendToMQ(publicInfo, commonAttr, userIp, clUserList.get(0), BuryingActionType.XY_REGISTER,"3");
            }
            sendToMQ(publicInfo, commonAttr, userIp, clUserList.get(0), BuryingActionType.XY_LOGIN,"3");
        } catch (Exception e) {
            log.error("?????????????????????????????????{}", e);
        }
        return result;
    }

    /**
     * ?????????????????????????????????????????????????????????????????????
     * @param uuid
     * @param typeEnum
     */
    private void updateUserDevice(String uuid, UserFromEnums typeEnum, ClUserVo clUser) {
        //????????????????????????????????????
        String clloName = getMongoCollection(typeEnum);
        Query query = new Query();
        query.addCriteria(Criteria.where("uuid").is(uuid));
        query.addCriteria(Criteria.where("user_type").is(0));
        Update update = new Update();
        update.set("user_type", 1).set("register_time", DateUtil.yyyy_MM_ddBHHMMSS());
        UserRefInfo tmp = mongoTemplate.findAndModify(query, update, UserRefInfo.class, clloName);
        if (tmp == null) {
            if (StringUtils.isBlank(clUser.getDistinctId())) {
                throw new NullPointerException("????????????id???????????????????????????????????????");
            }

            query = new Query();
            query.addCriteria(Criteria.where("uuid").is(uuid));
            long count = mongoTemplate.count(query, clloName);
            if (count == 0) {
                throw new NullPointerException("????????????id????????????");
            }

            query = new Query();
            query.addCriteria(Criteria.where("distinct_id").is(clUser.getDistinctId()));
            count = mongoTemplate.count(query, getDeviceMongoCollection(typeEnum));
            if (count == 0) {
                throw new NullPointerException("????????????id???????????????????????????????????????");
            }
            //???????????????id?????????id?????????????????????????????????????????????
            UserRefInfo userRefInfo = new UserRefInfo();
            userRefInfo.setInit_distinct_id(clUser.getDistinctId());
            userRefInfo.setUser_type(1);
            userRefInfo.setFrom(clUser.getType());
            userRefInfo.setInit_app_version(clUser.getAppVersion());
            userRefInfo.setInit_channel(clUser.getChannelId());
            //??????????????????????????????????????????(???????????????????????????md5???????????????)
            userRefInfo.setInit_package_name(clUser.getAppPackage());
            userRefInfo.setCreate_time(DateUtil.yyyy_MM_ddBHHMMSS());
            userRefInfo.setRegister_time(userRefInfo.getCreate_time());
            uuid= userUUIDService.add(userRefInfo, clloName);
            clUser.setUuid(uuid);
        }
    }

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        int a = resetUserNameAndImage(userId);
        int b = deleteUserVideoFavoritesDatas(userId);
        int c = deleteUserCommontDatas(userId);
        int d = deleteUserCommontReply(userId);
        log.info("?????????userID=" + userId + "?????????" + (a > 0 ? "??????" : "??????") + "?????????????????????" + b + "??????????????????" + c + "??????????????????" + d + "???");
    }


    private int deleteUserVideoFavoritesDatas(Long userId) {
        return clUserVideosMapper.delete(Wrappers.<ClUserVideos>query().eq("user_id", userId));
    }

    private int deleteUserCommontDatas(Long userId) {
        return clUserCommentMapper.delete(Wrappers.<ClUserComment>query().eq("user_id", userId));
    }

    private int deleteUserCommontReply(Long userId) {
        return commentReplyMapper.delete(Wrappers.<CommentReply>query().eq("from_uid", userId).or().eq("to_from_uid", userId));
    }

    private int resetUserNameAndImage(Long userId) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("id", userId);
        List<ClUserVo> users = clUserMapper.findClUserList(paramMap);
        if (CollectionUtils.isNotEmpty(users)) {
            ClUserVo user = users.get(0);
            int num = (int) ((Math.random() * 9 + 1) * 100000);
            String imgUrlStr = Global.getValue("defaultImage");
            if (StringUtils.isNotBlank(imgUrlStr)) {
                String[] imgUrls = imgUrlStr.split(",");
                Random r = new Random();
                int imgNum = r.nextInt(15);
                user.setName(String.valueOf(num));
                user.setSign("reset");
                user.setImgUrl(imgUrls[imgNum]);
                return clUserMapper.updateClUser(user);
            }
        }
        return 0;
    }

    public void sendToMQ(String publicInfo, String commonAttr, String userIp, ClUserVo clUserVo, String actionId,String type) {
        byte[] presetPropByte = Base64Utils.decodeFromString(commonAttr);
        commonAttr = new String(presetPropByte, StandardCharsets.UTF_8);
        String[] attr = commonAttr.split("::");
        JSONObject event = new JSONObject();
        event.put("change_channel", attr[0]);
        event.put("action_id", actionId);
        event.put("is_login", BuryingActionType.XY_LOGIN.equals(actionId) ? "true" : "false");
        event.put("uuid", clUserVo.getUuid());
        if (BuryingActionType.XY_LOGIN.equals(actionId)){
            event.put("type", type);
        }
        event.put("creat_time", DateUtil.yyyy_MM_ddBHHMMSS());
        event.put("app_version", attr[4]);
        event.put("last_view", attr[5]);
        event.put("view", attr[6]);
        event.put("phone", StringUtils.isBlank(clUserVo.getLoginName()) ? "null" : clUserVo.getLoginName());
        event.put("register_time", StringUtils.isBlank(clUserVo.getCreateTime()) ? "null" : clUserVo.getCreateTime());
        event.put("nick", StringUtils.isBlank(clUserVo.getName()) ? "null" : clUserVo.getName());
        JSONObject p = new JSONObject();
        p.put("publicInfo", publicInfo);
        p.put("event", event.toJSONString());
        p.put("userIp", userIp);
        String jsonStr = p.toJSONString();
        rabbitTemplate.convertAndSend(RabbitMQConstant.BURYPOINT_EXCHANGE, RabbitMQConstant.BURYPOINT_RUTE_KEY, jsonStr);
    }

    private String buildAnonymousId() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

}