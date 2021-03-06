package com.miguan.idmapping.service.impl;

import com.alibaba.fastjson.JSON;
import com.cgcg.context.util.MD5Utils;
import com.google.common.collect.Maps;
import com.miguan.idmapping.common.constants.RedisKeyConstant;
import com.miguan.idmapping.common.enums.UserFromEnums;
import com.miguan.idmapping.common.utils.DateUtil;
import com.miguan.idmapping.dto.RegAnonymousDto;
import com.miguan.idmapping.entity.ClDevice;
import com.miguan.idmapping.service.ClDeviceService;
import com.miguan.idmapping.service.IAnonymousRegister;
import com.miguan.idmapping.service.RedisService;
import com.miguan.idmapping.task.BloomFilterTask;
import com.miguan.idmapping.vo.AppDeviceVo;
import com.miguan.idmapping.vo.DeviceInfoVo;
import com.miguan.idmapping.vo.UserRefInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bson.BsonDocument;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

import static com.miguan.idmapping.common.utils.DbCollectionUtils.getAppDeviceMongoCollection;
import static com.miguan.idmapping.common.utils.DbCollectionUtils.getDeviceMongoCollection;
import static com.miguan.idmapping.common.utils.DbCollectionUtils.getMongoCollection;
import static org.apache.commons.lang3.StringUtils.isAllBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;

/**
 * @author zhongli
 * @date 2020-07-21 
 *
 */
@Service
@Slf4j
public class AnonymousRegisterImpl implements IAnonymousRegister {

    @Resource
    private MongoTemplate mongoTemplate;
    @Autowired
    private UserUUIDServiceImpl userUUIDService;
    @Autowired
    private BloomFilterService bloomFilterService;
    @Autowired
    private ClDeviceService clDeviceService;
    @Resource
    private RedisService redisService;

    @Override
    public Map<String, String> register(RegAnonymousDto regDeviceInfo) {
        UserFromEnums typeEnum = UserFromEnums.toEnums(regDeviceInfo.getType());
        if (typeEnum == null) {
            throw new NullPointerException("???????????????????????????type?????????android,ios,h5,weixin,xiaochengxu,web");
        }
        if (typeEnum != UserFromEnums.ANDROID && typeEnum != UserFromEnums.IOS && isBlank(regDeviceInfo.getNumber1())) {
            throw new NullPointerException("number1 ???????????????");
        } else if (typeEnum == UserFromEnums.IOS && isAllBlank(regDeviceInfo.getNumber1(), regDeviceInfo.getNumber2())) {
            throw new NullPointerException("number1, number2 ??????????????????");
        } else if (isAllBlank(regDeviceInfo.getNumber1(), regDeviceInfo.getNumber2())) {
            throw new NullPointerException("number1...4 ??????????????????");
        }
        String cloll = getDeviceMongoCollection(typeEnum);
        String refColl = getMongoCollection(typeEnum);
        String distinctId = null;
        //lzhong ??????????????????????????????distinctId????????????????????????????????????distinctId
        if (StringUtils.isNotBlank(regDeviceInfo.getDistinctId())) {
            distinctId = regDeviceInfo.getDistinctId().trim();
            Query query = new Query();
            query.addCriteria(Criteria.where("distinct_id").is(distinctId));
            long count = mongoTemplate.count(query, cloll);
            if (count == 0) {
                distinctId = null;
            }
        }
        //lzhong ????????????????????????????????????distinctId????????????????????????distinctId
        distinctId = distinctId == null ? buildDeviceMd5(typeEnum, regDeviceInfo) : distinctId;
        //??????md5????????????????????????????????????????????????????????????????????????????????????id???????????????type??????????????????????????????????????????
        Query query = new Query(Criteria.where("init_distinct_id").is(distinctId).and("user_type").is(0));
        query.fields().include("uuid").exclude("_id");
        UserRefInfo userRefInfo = mongoTemplate.findOne(query, UserRefInfo.class, getMongoCollection(typeEnum));
        String uuid;
        if (userRefInfo != null) {
            uuid = userRefInfo.getUuid();
        } else {
            uuid = bindDeviceUUID(regDeviceInfo, distinctId, refColl);
            redisService.set(RedisKeyConstant.UUID_KEY + uuid, "1", RedisKeyConstant.UUID_SECONDS);
        }
        if (StringUtils.isBlank(regDeviceInfo.getDeviceId())) {
            regDeviceInfo.setDeviceId(userUUIDService.getDeviceId(typeEnum, regDeviceInfo));
        }
        log.warn("??????distinctId({}) deviceid={}  uuid={}", distinctId, regDeviceInfo.getDeviceId(), uuid);
        //???????????????id???????????????
        Map<String, String> map = Maps.newHashMapWithExpectedSize(2);
        map.put("distinct_id", distinctId);
        map.put("uuid", uuid);
        //lzhong ????????????
        DeviceInfoVo deviceInfoVo = regDevice(regDeviceInfo, distinctId, cloll);
        AppDeviceVo appDeviceVo = bindAppDevice(typeEnum, regDeviceInfo, distinctId);
        fillResultForNewDeviceOrNewApp(deviceInfoVo, appDeviceVo, regDeviceInfo, map);
        log.warn("?????????????????????????????????{}", JSON.toJSONString(map));
        return map;
    }

    public void fillResultForNewDeviceOrNewApp(DeviceInfoVo deviceInfoVo, AppDeviceVo appDeviceVo, RegAnonymousDto regDeviceInfo, Map<String, String> map) {
        String today = DateUtil.yyyy_MM_dd();
        //???????????????
        map.put("is_new", deviceInfoVo.getCreateTime().startsWith(today) ? Boolean.TRUE.toString() : Boolean.FALSE.toString());
        //?????????????????????????????????
        map.put("is_new_app", appDeviceVo.getCreateTime().startsWith(today) ? Boolean.TRUE.toString() : Boolean.FALSE.toString());
        //????????????????????????????????????
        map.put("init_channel", appDeviceVo.getChannel());
        //????????????????????????????????????
        map.put("first_visit_time", appDeviceVo.getCreateTime().concat(",").concat(deviceInfoVo.getCreateTime()));
    }

    /**
     * ????????????
     * @param regDeviceInfo
     * @param distinctId
     * @return
     */
    private DeviceInfoVo regDevice(RegAnonymousDto regDeviceInfo, String distinctId, String collectionName) {
        Query query = new Query();
        query.addCriteria(Criteria.where("distinct_id").is(distinctId));
        DeviceInfoVo deviceInfoVo = mongoTemplate.findOne(query, DeviceInfoVo.class, collectionName);
        if (deviceInfoVo != null) {
            clDeviceService.updateDistinctId(distinctId, regDeviceInfo.getDeviceId(), regDeviceInfo.getPackageName());  //??????cl_device??????distinctId??????
            return deviceInfoVo;
        }
        deviceInfoVo = new DeviceInfoVo();
        deviceInfoVo.setDistinct_id(distinctId);
        deviceInfoVo.setType(regDeviceInfo.getType());
        deviceInfoVo.setChannel(regDeviceInfo.getChannel());
        deviceInfoVo.setOs(regDeviceInfo.getOs());
        deviceInfoVo.setManufacturer(regDeviceInfo.getManufacturer());
        deviceInfoVo.setScreenHeight(regDeviceInfo.getScreenHeight());
        deviceInfoVo.setScreenWidth(regDeviceInfo.getScreenWidth());
        deviceInfoVo.setBrand(regDeviceInfo.getBrand());
        deviceInfoVo.setModel(regDeviceInfo.getModel());
        deviceInfoVo.setNumber1(regDeviceInfo.getNumber1());
        deviceInfoVo.setNumber2(regDeviceInfo.getNumber2());
        deviceInfoVo.setNumber3(regDeviceInfo.getNumber3());
        deviceInfoVo.setNumber4(regDeviceInfo.getNumber4());
        deviceInfoVo.setNumber5(regDeviceInfo.getNumber5());
        deviceInfoVo.setNumber6(regDeviceInfo.getNumber6());
        deviceInfoVo.setNumber7(regDeviceInfo.getNumber7());
        deviceInfoVo.setNumber8(regDeviceInfo.getNumber8());
        deviceInfoVo.setNumber9(regDeviceInfo.getNumber9());
        deviceInfoVo.setNumber10(regDeviceInfo.getNumber10());
        deviceInfoVo.setCreateTime(getCreateTime(regDeviceInfo.getDeviceId(), regDeviceInfo.getPackageName()));
        deviceInfoVo.setDistinct_id(distinctId);
        Update update = new BasicUpdate(new Document("$setOnInsert", BsonDocument.parse(JSON.toJSONString(deviceInfoVo))));
        clDeviceService.updateDistinctId(distinctId, regDeviceInfo.getDeviceId(), regDeviceInfo.getPackageName());  //??????cl_device??????distinctId??????
        mongoTemplate.upsert(query, update, DeviceInfoVo.class, collectionName);
        return deviceInfoVo;
    }


    /**
     * ???????????????uuid???????????????????????????????????????uuid
     * @param regDeviceInfo
     * @param distinctId
     * @param collectionName
     * @return
     */
    private String bindDeviceUUID(RegAnonymousDto regDeviceInfo, String distinctId, String collectionName) {
        //???????????????uuid????????????????????????id
        //???????????????id?????????id?????????????????????????????????????????????
        UserRefInfo userRefInfo = new UserRefInfo();
        userRefInfo.setInit_distinct_id(distinctId);
        userRefInfo.setUser_type(0);
        userRefInfo.setFrom(regDeviceInfo.getType());
        userRefInfo.setInit_app_version(regDeviceInfo.getAppVersion());
        userRefInfo.setInit_channel(regDeviceInfo.getChannel());
        //??????????????????????????????????????????(???????????????????????????md5???????????????)
        userRefInfo.setInit_package_name(regDeviceInfo.getPackageName());
        userRefInfo.setCreate_time(DateUtil.yyyy_MM_ddBHHMMSS());
        return userUUIDService.add(userRefInfo, collectionName);
    }

    /**
     * ????????????????????????
     * @param typeEnum
     * @param regDeviceInfo
     * @param distinctId
     * @return
     */
    private AppDeviceVo bindAppDevice(UserFromEnums typeEnum, RegAnonymousDto regDeviceInfo, String distinctId) {
        String collName = getAppDeviceMongoCollection(typeEnum);
        Query query = new Query(Criteria.where("distinct_id").is(distinctId).and("package_name").is(regDeviceInfo.getPackageName()));
        AppDeviceVo vo = mongoTemplate.findOne(query, AppDeviceVo.class, collName);
        if (vo == null) {
            vo = new AppDeviceVo();
            vo.setDistinct_id(distinctId);
            vo.setPackage_name(regDeviceInfo.getPackageName());
            vo.setChannel(regDeviceInfo.getChannel());
            vo.setCreateTime(getCreateTime(regDeviceInfo.getDeviceId(), regDeviceInfo.getPackageName()));
            mongoTemplate.insert(vo, collName);
        }
        return vo;
    }

    private String getCreateTime(String deviceId, String appPackage) {
        //?????????????????????????????????????????????
        boolean isExist = StringUtils.isBlank(deviceId) ? false : bloomFilterService.contain(deviceId, appPackage);
        if (isExist || (!isExist && BloomFilterTask.isRunning)) {
            ClDevice device = clDeviceService.findDevice(deviceId, appPackage);
            if (device != null) {
                return tool.util.DateUtil.dateStr4(device.getCreateTime());
            }
        }
        return DateUtil.yyyy_MM_ddBHHMMSS();
    }

    private String buildDeviceMd5(UserFromEnums userFromEnums, RegAnonymousDto deviceInfoDto) {
        String cStr;
        switch (userFromEnums) {
            case ANDROID: {
                cStr = new StringBuilder().append(deviceInfoDto.getOs()).append(deviceInfoDto.getManufacturer())
                        .append(deviceInfoDto.getScreenHeight()).append(deviceInfoDto.getScreenWidth())
                        .append(deviceInfoDto.getBrand()).append(deviceInfoDto.getModel())
                        .append(trimToEmpty(deviceInfoDto.getNumber1())).append(trimToEmpty(deviceInfoDto.getNumber2())).toString();
                break;
            }
            case IOS: {
                cStr = new StringBuilder().append(deviceInfoDto.getType())
                        .append(trimToEmpty(deviceInfoDto.getNumber1()))
                        .append(trimToEmpty(deviceInfoDto.getNumber2()))
                        .toString();
                break;
            }
            case H5:
            case WX:
            case XCX:
            case WEB:
            default:
                cStr = new StringBuilder().append(deviceInfoDto.getType()).append(trimToEmpty(deviceInfoDto.getNumber1())).toString();
        }
        return MD5Utils.stringToMD5(cStr);
    }
}
