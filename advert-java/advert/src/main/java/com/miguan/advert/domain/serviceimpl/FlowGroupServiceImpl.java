package com.miguan.advert.domain.serviceimpl;

import cn.jiguang.common.utils.StringUtils;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.miguan.advert.common.abplat.ABPlatFormService;
import com.miguan.advert.common.constants.FlowGroupConstant;
import com.miguan.advert.common.constants.TableInfoConstant;
import com.miguan.advert.common.construct.LogOperationConstruct;
import com.miguan.advert.common.util.*;
import com.miguan.advert.domain.mapper.AdOperationLogMapper;
import com.miguan.advert.domain.mapper.AdPlatMapper;
import com.miguan.advert.domain.mapper.FlowTestMapper;
import com.miguan.advert.domain.mapper.PositionInfoMapper;
import com.miguan.advert.domain.pojo.AdAdvertCode;
import com.miguan.advert.domain.pojo.AdAdvertFlowConfig;
import com.miguan.advert.domain.pojo.AdAdvertTestConfig;
import com.miguan.advert.domain.pojo.AdTestCodeRelation;
import com.miguan.advert.domain.service.FlowGroupService;
import com.miguan.advert.domain.service.TableInfoService;
import com.miguan.advert.domain.service.ToolMofangService;
import com.miguan.advert.domain.vo.ChannelInfoVo;
import com.miguan.advert.domain.vo.interactive.AdMultiDimenVo;
import com.miguan.advert.domain.vo.interactive.AdProfitVo;
import com.miguan.advert.domain.vo.request.AdAdvertFlowConfigVo;
import com.miguan.advert.domain.vo.request.AdAdvertTestConfigVo;
import com.miguan.advert.domain.vo.result.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.elasticsearch.common.Strings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Collectors;
import com.miguan.advert.domain.service.DspReportService;
import com.miguan.advert.domain.service.ReportService;
import com.miguan.advert.domain.service.AdMultiDimenService;
import com.miguan.advert.domain.vo.interactive.AdProfitVo;
import com.miguan.advert.domain.mapper.AdAdvertCodeMapper;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * @program: advert-java
 * @description: ????????????????????????
 * @author: suhj
 * @create: 2020-09-27 10:36
 **/
@Slf4j
@Service
public class FlowGroupServiceImpl implements FlowGroupService {

    @Resource
    FlowTestMapper flowTestMapper;

    @Resource
    AdPlatMapper adPlatMapper;

    @Resource
    ABPlatFormService abPlatFormService;

    @Resource
    private ToolMofangService toolMofangService;

    @Resource
    private TableInfoService tableInfoService;

    @Resource
    private AdOperationLogMapper adOperationLogMapper;

    @Resource
    private PositionInfoMapper positionInfoMapper;

    @Resource
    private DspReportService dspReportService;

    @Resource
    private ReportService reportService;

    @Resource
    private AdMultiDimenService adMultiDimenService;

    @Resource
    private AdAdvertCodeMapper adAdvertCodeMapper;

    @Value("${strategy-server.count_multi_ecpm}")
    private String countMultiEcpmUrl;

    @Resource
    private RestTemplate restTemplate;


    @Override
    public ResultMap<List<ABFlowGroupVo>> getLst(String positionId) {
        List<ABFlowGroupVo> abFlowGroupVoList = Lists.newArrayList();

        //?????????????????????
        List<AdvCodeInfoVo> defaultPosCodeLstVos = getAdvCodeInfoVos(positionId);

        AppAdPositionVo positionInfo = positionInfoMapper.getPositionById(Integer.parseInt(positionId));

        //??????????????????
        List<AdAdvertFlowConfig> adAdvertFlowConfigs = flowTestMapper.getAdvFlowConfLst(positionId,null,null, null);

        //?????????????????????
        for (int i = 0; i < adAdvertFlowConfigs.size(); i++) {

            AdAdvertFlowConfig flowConfig = adAdvertFlowConfigs.get(i);

            Map<String, Object> abFlowMap = Maps.newHashMap();

            if(!StringUtil.isEmpty(flowConfig.getAb_flow_id())){
                //???????????????????????????
                ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(flowConfig.getAb_flow_id());

                if(resultMap.getCode() != 200){
                    return ResultMap.error(resultMap.getMessage());
                }

                abFlowMap =resultMap.getData();
            }

            ABFlowGroupVo abFlowGroupVo = (ABFlowGroupVo) Utilities.deepClone(returnFlowTest(flowConfig,defaultPosCodeLstVos,abFlowMap,positionInfo));

            abFlowGroupVoList.add(abFlowGroupVo);
        }

        return ResultMap.success(abFlowGroupVoList);

    }


    /**
     * ??????????????????????????????
     * @param flowConfig
     * @param defaultPosCodeLstVos
     * @param abFlowMap
     * @param positionInfo
     * @return
     */
    private ABFlowGroupVo returnFlowTest(AdAdvertFlowConfig flowConfig, List<AdvCodeInfoVo> defaultPosCodeLstVos, Map<String, Object> abFlowMap, AppAdPositionVo positionInfo){

        String channelIds = "",channel2Ids = "",version1 = "",version2 = "",version3 = "",version4 = "",versionFlag = "";
        List<Map<String,Object>> testArr = Lists.newArrayList();
        if(MapUtils.isNotEmpty(abFlowMap)){
            channelIds = MapUtils.getString(abFlowMap,"channel");
            channel2Ids = MapUtils.getString(abFlowMap,"channel2");
            version1 = MapUtils.getString(abFlowMap,"version1");
            version2 = MapUtils.getString(abFlowMap,"version2");
            version3 = MapUtils.getString(abFlowMap,"version3");
            version4 = MapUtils.getString(abFlowMap,"version4");
            versionFlag = MapUtils.getString(abFlowMap,"versionFlag");
            testArr = (List<Map<String, Object>>) abFlowMap.get("testArr");
        }
        String channelNames = getChannelNames(channelIds);
        String channelNames2 = getChannelNames(channel2Ids);
        //????????????
        int flowType = 1;
        List<AdvCodeInfoVo> newAdvCodes = Lists.newArrayList();
        if(FlowGroupConstant.DEFAULT_GROUP_TYPE == flowConfig.getType()){
            newAdvCodes.addAll(defaultPosCodeLstVos);
        }else if(FlowGroupConstant.HANDLER_GROUP_TYPE == flowConfig.getType()) {//????????????????????????????????????????????????
            newAdvCodes = getAdvCodesByFilter(defaultPosCodeLstVos, channelIds,channel2Ids, version1, version2,version3,version4,versionFlag);
            flowType = 2;
        }
        //????????????????????????
        List<AdAdvertTestConfig> allTestConfigs = flowTestMapper.getAdvTestConfLst(String.valueOf(flowConfig.getId()),null);

        //??????????????????????????????????????????
        List<ABTestGroupVo> newTestConfs = getAdvTestConfs(allTestConfigs, newAdvCodes, testArr);

        //?????????????????????
        //List<AdAdvertTestConfig> defaultTestConfigs = allTestConfigs.stream().filter(
        //        testConf -> "0".equals(String.valueOf(testConf.getType()))).collect(Collectors.toList());
        //???????????????????????????????????????
        //List<ABTestGroupVo> defaultTestGroupVoList = getAbTestGroupVos(defaultTestConfigs, defaultPosCodeLstVos);

        String appVersion = "??????";
        if(StringUtil.isNotEmpty(version1) && StringUtil.isNotEmpty(version2) ){
            appVersion = version1 + "-" + version2;
        }else if(StringUtil.isNotEmpty(version1) && StringUtil.isEmpty(version2)){
            appVersion = versionFlag+version1;
        }else if(StringUtil.isEmpty(version1) && StringUtil.isNotEmpty(version2)){
            appVersion = versionFlag+version2;
        }else if (StringUtil.isNotEmpty(version3)){
            appVersion = versionFlag+version3;
        }else if (StringUtil.isNotEmpty(version4)){
            appVersion = versionFlag+version4;
        }

        if(StringUtils.isNotEmpty(channelNames2) && !"??????".equals(channelNames2)){
            channelNames = "??????:"+channelNames2;
        } else if( StringUtils.isNotEmpty(channelNames) && !"??????".equals(channelNames)){
            channelNames = "??????:"+channelNames;
        }

        ABFlowGroupVo abFlowGroupVo = new ABFlowGroupVo(flowConfig.getId(),flowConfig.getName(),
                channelNames,flowType, flowConfig.getTest_state(),appVersion, flowConfig.getAb_flow_id(),newAdvCodes,newTestConfs
                ,flowConfig.getPub_time(),flowConfig.getStatus(),positionInfo == null ? null : positionInfo.getMobile_type(),flowConfig.getOpen_status());

        return abFlowGroupVo;
    }

    /**
     * ??????????????????
     * @param channelIds
     * @return
     */
    private String getChannelNames(String channelIds) {
        String channelNames = "";
        if(StringUtil.isEmpty(channelIds)){
            channelNames = "??????";
        }
        List<ChannelInfoVo> channelInfoVoList = toolMofangService.findChannelInfoByKeys(channelIds);
        if (CollectionUtils.isNotEmpty(channelInfoVoList)) {
            channelNames = channelInfoVoList.stream().map(channelInfoVo ->
                    channelInfoVo.getChannelName()).collect(Collectors.joining(","));
        }
        return channelNames;
    }

    /**
     * ?????????????????????
     * @param positionId
     * @return
     */
    private List<AdvCodeInfoVo> getAdvCodeInfoVos(String positionId) {
        List<AdvCodeInfoVo> defaultPosCodeLstVos = Lists.newArrayList();
        List<AdAdvertCode> advCodeInfoVoLst = flowTestMapper.getAdvCodeInfoVoLst(positionId);
        if(CollectionUtils.isEmpty(advCodeInfoVoLst)){
            return defaultPosCodeLstVos;
        }
        //?????? ?????????????????????????????????
        advCodeInfoVoLst.forEach(adv -> {

            String platName = adPlatMapper.findPlatNameByPlatKey(adv.getPlat_key());
            String channelNames = getChannelNames(adv.getChannel_ids());
            AdvCodeInfoVo advCodeInfoVo = new AdvCodeInfoVo(
                    adv.getId(),0,0,0,adv.getPlat_key(),platName, adv.getAd_id(),adv.getLadder(),adv.getLadder_price(),adv.getChannel_type(),
                    adv.getChannel_ids(), channelNames,adv.getType_key(),adv.getVersion1(),adv.getVersion2(),adv.getPermission(),adv.getPut_in(),
                    DateUtils.parseDateToStr(adv.getCreated_at(),DateUtils.DATEFORMAT_STR_010),DateUtils.parseDateToStr(adv.getUpdated_at(),DateUtils.DATEFORMAT_STR_010), 0d);
            defaultPosCodeLstVos.add(advCodeInfoVo);
        });
        return defaultPosCodeLstVos;
    }

    /**
     * ????????????????????????
     * @param flowId
     * @param name
     * @param ip
     */
    @Override
    public ResultMap<ABFlowGroupVo> updateFlowName(String flowId, String abFlowId, String name, String ip) {
        String channelNames = "??????", appVersion = "??????";
        List<AdAdvertFlowConfig> flowConfigs = flowTestMapper.getAdvFlowConfLst(null,null,flowId, null);
        AdAdvertFlowConfig adAdvertFlow = flowConfigs.get(0);

        List<AdvCodeInfoVo> defaultPosCodeLstVos = getAdvCodeInfoVos(String.valueOf(adAdvertFlow.getPosition_id()));

        ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(abFlowId);
        if(resultMap.getCode() != 200){
            return ResultMap.error(resultMap.getMessage());
        }

        Map<String, Object> abFlowMap = resultMap.getData();

        Map<String,String> expInfo = (Map<String,String>) abFlowMap.get("exp_info");
        List<String> appPackages = flowTestMapper.findAppPackageByPosId(String.valueOf(adAdvertFlow.getPosition_id()));
        if (MapUtils.isNotEmpty(expInfo) &&
                String.valueOf(expInfo.get("app_package_names")).indexOf(appPackages.get(0)) == -1) {
            return ResultMap.error("AB????????????????????????????????????=" + expInfo.get("app_package_names")
                    + "???????????????????????????????????????="+ appPackages.get(0));
        }
        String abFlowIdBefore = flowConfigs.get(0).getAb_flow_id();
        String nameBefore = flowConfigs.get(0).getName();
        //?????????????????????????????????????????????
        adAdvertFlow.setAb_flow_id(abFlowId);
        adAdvertFlow.setName(name);
        flowTestMapper.updateFlowGroup(adAdvertFlow);


        //??????????????????(????????????) start
        LogOperationConstruct logConstruct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
        logConstruct.logUpdate(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,TableInfoConstant.NAME,nameBefore,adAdvertFlow.getName());
        logConstruct.logUpdate(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,TableInfoConstant.AB_FLOW_ID,abFlowIdBefore,adAdvertFlow.getAb_flow_id());
        logConstruct.saveLog(logConstruct.PATH_ADD_FLOW_ID,ip);
        //??????????????????(????????????) end


        List<Map<String,Object>> testArr = (List<Map<String,Object>>) abFlowMap.get("testArr");

        //??????????????????
        String abChannelNames = getChannelNames(MapUtils.getString(abFlowMap,"channel"));
        if(StringUtil.isNotEmpty(abChannelNames)){
            channelNames = abChannelNames;
        }
        String version1 = MapUtils.getString(abFlowMap,"version1");
        String version2 = MapUtils.getString(abFlowMap,"version2");

        if(StringUtil.isNotEmpty(version1) && StringUtil.isNotEmpty(version2) ){
            appVersion = version1 + "-" + version2;
        }else if(StringUtil.isNotEmpty(version1) && StringUtil.isEmpty(version2)){
            appVersion = "????????????"+version1;
        }else if(StringUtil.isEmpty(version1) && StringUtil.isNotEmpty(version2)){
            appVersion = "????????????"+version2;
        }

        //????????????
        int flowType = 1;
        List<AdvCodeInfoVo> newAdvCodes = Lists.newArrayList();
        if(FlowGroupConstant.DEFAULT_GROUP_TYPE == adAdvertFlow.getType()){
            newAdvCodes.addAll(defaultPosCodeLstVos);
        }else if(FlowGroupConstant.HANDLER_GROUP_TYPE == adAdvertFlow.getType()) {//????????????????????????????????????????????????
            newAdvCodes = getAdvCodesByFilter(defaultPosCodeLstVos,
                    MapUtils.getString(abFlowMap,"channel"),null, version1, version2,null,null, null);
            flowType = 2;
        }
        //????????????????????????
        List<AdAdvertTestConfig> allTestConfigs = flowTestMapper.getAdvTestConfLst(String.valueOf(adAdvertFlow.getId()),null);

        //??????????????????????????????????????????
        List<ABTestGroupVo> newTestConfs = getAdvTestConfs(allTestConfigs, newAdvCodes, testArr);
        //???????????????
        ABFlowGroupVo retMap = new ABFlowGroupVo(adAdvertFlow.getId(), name,
                channelNames, adAdvertFlow.getType(), 1, appVersion, adAdvertFlow.getAb_flow_id(), defaultPosCodeLstVos, newTestConfs,adAdvertFlow.getPub_time(),adAdvertFlow.getStatus(),null,null);
        return ResultMap.success(retMap);

    }

    @Transactional
    @Override
    public ResultMap<ABFlowGroupVo> insertFlowGroup(String positionId, String name, String abFlowId, String type, String ip) {

        List<AdvCodeInfoVo> defaultPosCodeLstVos = getAdvCodeInfoVos(positionId);

        List<AdAdvertFlowConfig>  flowConfLst = flowTestMapper.getAdvFlowConfLst(positionId,type,null, null);

        //???????????????????????????????????????????????????
        if(FlowGroupConstant.DEFAULT_GROUP_TYPE == Integer.parseInt(type)){
            if(CollectionUtils.isEmpty(flowConfLst)){
                //???????????????????????????????????????????????????
/*
                AdAdvertFlowConfig flowConfig =
                        new AdAdvertFlowConfig(null,DEFAULT_FLOW_GROUP_NAME,
                                Integer.parseInt(positionId),abFlowId,1,0,null,null,1);
                flowTestMapper.insertFlowGroup(flowConfig);
                AdAdvertTestConfig testConfig = new AdAdvertTestConfig(
                        null,String.valueOf(flowConfig.getId()),null,2,1,null,null,1);
                flowTestMapper.insertTestGroup(testConfig);
*/

                //??????AB??????????????????
                AdAdvertFlowConfig abFlowConfig =
                        new AdAdvertFlowConfig(null,FlowGroupConstant.DEFAULT_FLOW_GROUP_NAME,
                                Integer.parseInt(positionId),abFlowId,1,0,null,null,1);
                flowTestMapper.insertFlowGroup(abFlowConfig);
                AdAdvertTestConfig abTestConfig = new AdAdvertTestConfig(
                        null,String.valueOf(abFlowConfig.getId()),null,2,1,null,null,1);
                flowTestMapper.insertTestGroup(abTestConfig);

                LogOperationConstruct construct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
                construct.logInsert(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,abFlowConfig);
                construct.logInsert(TableInfoConstant.AD_ADVERT_TEST_CONFIG,abTestConfig);
                construct.saveLog(LogOperationConstruct.PATH_ADD_FLOW_GROUP,ip,1);
            }
            return ResultMap.success();

        }


        if(StringUtils.isEmpty(abFlowId)){
            return ResultMap.error("AB??????ID????????????");
        }

        if(flowConfLst.stream().anyMatch(flow -> flow.getAb_flow_id().equals(abFlowId))){
            return ResultMap.error("??????????????????????????????AB??????ID");
        }

        ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(abFlowId);
        if(resultMap.getCode() != 200){
            return ResultMap.error(resultMap.getMessage());
        }
        Map<String, Object> abFlowMap = resultMap.getData();
        String channelNames = "??????", appVersion = "??????";
        Map<String,String> expInfo = (Map<String,String>) abFlowMap.get("exp_info");
        List<String> appPackages = flowTestMapper.findAppPackageByPosId(positionId);
        if (MapUtils.isNotEmpty(expInfo) &&
                String.valueOf(expInfo.get("app_package_names")).indexOf(appPackages.get(0)) == -1) {
            return ResultMap.error("AB????????????????????????????????????=" + expInfo.get("app_package_names")
                    + "???????????????????????????????????????="+ appPackages.get(0));
        }

        //??????????????????
        String abChannelNames = getChannelNames(MapUtils.getString(abFlowMap,"channel"));
        if(StringUtil.isNotEmpty(abChannelNames)){
            channelNames = abChannelNames;
        }
        String version1 = MapUtils.getString(abFlowMap,"version1");
        String version2 = MapUtils.getString(abFlowMap,"version2");


        //?????????????????????  ??????????????????
        List<AdvCodeInfoVo> newAdvCodes = getAdvCodesByFilter(defaultPosCodeLstVos,
                    MapUtils.getString(abFlowMap,"channel"),null, version1, version2,null,null, null);

        //????????????????????????????????????????????????
/*            if(HANDLER_GROUP_TYPE == Integer.parseInt(type)){
            if(StringUtil.isEmpty(version1) && StringUtil.isEmpty(version2)
                    && StringUtil.isEmpty(abChannelNames)){
                return ResultMap.error("???????????????????????????AB????????????????????????????????????????????????");
            }
        }*/

        if(StringUtil.isNotEmpty(version1) && StringUtil.isNotEmpty(version2) ){
            appVersion = version1 + "-" + version2;
        }else if(StringUtil.isNotEmpty(version1) && StringUtil.isEmpty(version2)){
            appVersion = "????????????"+version1;
        }else if(StringUtil.isEmpty(version1) && StringUtil.isNotEmpty(version2)){
            appVersion = "????????????"+version2;
        }

        //????????????????????????
        AdAdvertFlowConfig flowConfig =
                new AdAdvertFlowConfig(null,name,
                        Integer.parseInt(positionId),abFlowId,2,1,null,null,1);
        flowTestMapper.insertFlowGroup(flowConfig);


        AdAdvertTestConfig testConfig = new AdAdvertTestConfig(
                null,String.valueOf(flowConfig.getId()),null,2,1,null,null,1);
        flowTestMapper.insertTestGroup(testConfig);

        LogOperationConstruct construct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
        construct.logInsert(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,flowConfig);
        construct.logInsert(TableInfoConstant.AD_ADVERT_TEST_CONFIG,testConfig);
        construct.saveLog(LogOperationConstruct.PATH_ADD_FLOW_GROUP,ip,1);

        //???????????????????????????????????????
        List<ABTestGroupVo> abTestGroupVoList = Lists.newArrayList();

        ABTestGroupVo abTestGroupVo = new ABTestGroupVo(
                testConfig.getId(),testConfig.getAb_test_id(),null,testConfig.getComputer(), testConfig.getType(), Lists.newArrayList(),null);
        abTestGroupVoList.add(abTestGroupVo);

        //???????????????
        ABFlowGroupVo retMap = new ABFlowGroupVo(flowConfig.getId(),name,
                channelNames, Integer.parseInt(type), 1,appVersion, flowConfig.getAb_flow_id(),newAdvCodes, abTestGroupVoList,flowConfig.getPub_time(),flowConfig.getStatus(),null,null);
        return ResultMap.success(retMap);
    }

    @Transactional
    @Override
    public void deleteFlowGroup(String flowId, String ip) {
        //??????????????????
        //????????????config
        LogOperationConstruct construct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
        AdAdvertFlowConfig config = flowTestMapper.findAdFlowConfigById(flowId);
        flowTestMapper.deleteFlowById(flowId);
        construct.logDelete(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,config);

        List<AdAdvertTestConfig> testConfigs = flowTestMapper.getAdvTestConfLst(flowId,null);
        if(CollectionUtils.isNotEmpty(testConfigs)){
            testConfigs.forEach(conf->{
                //????????????test???relation
                List<AdTestCodeRelation> relations = flowTestMapper.findAdTestCodeRelationByConfigId(String.valueOf(conf.getId()));
                flowTestMapper.deleteTestById(String.valueOf(conf.getId()));
                flowTestMapper.deleteConfigByConfId(String.valueOf(conf.getId()));
                construct.logDelete(TableInfoConstant.AD_ADVERT_TEST_CONFIG,conf);
                construct.logDeleteList(TableInfoConstant.AD_TEST_CODE_RELATION,relations);
            });
        }
        construct.saveLog(LogOperationConstruct.PATH_DELETE_FLOW_GROUP,ip,2);
    }

    @Transactional
    @Override
    public ResultMap<ABFlowGroupVo> addEditTestGroup(String flowId, List<String> testIdLst, String testState, String ip) {

        List<AdAdvertFlowConfig> flowConfigs = flowTestMapper.getAdvFlowConfLst(null,null,flowId, null);
        AdAdvertFlowConfig adAdvertFlow = flowConfigs.get(0);
        //????????????????????????????????????????????????????????????AB??????ID??????????????????
        if(StringUtil.isEmpty(adAdvertFlow.getAb_flow_id())){
            return ResultMap.error("AB??????ID????????????");
        }

        //???????????????????????????
        ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(adAdvertFlow.getAb_flow_id());

        if(resultMap.getCode() != 200){
            return ResultMap.error(resultMap.getMessage());
        }

        Map<String, Object> abFlowMap =resultMap.getData();

        List<Map<String,String>> testArr = (List<Map<String,String>>) abFlowMap.get("testArr");
        if (CollectionUtils.isEmpty(testArr)) {
            return ResultMap.error("AB???????????????????????????????????????ID???"+String.join(",",testIdLst));
        }else{
            List<String> expGrpId = testArr.stream().map(test -> String.valueOf(test.get("id"))).collect(Collectors.toList());
            List<String> newTestIdLst = Lists.newArrayList();
            for (String testId : testIdLst) {
                if(!expGrpId.contains(testId)){
                    newTestIdLst.add(testId);
                }
            }
            if(CollectionUtils.isNotEmpty(newTestIdLst)){
                return ResultMap.error("AB???????????????????????????????????????ID???"+String.join(",",newTestIdLst));
            }
        }

        //?????????????????????????????????
        List<AdAdvertTestConfig> advTestConfigs = flowTestMapper.getAdvTestConfLst(flowId,null);

        LogOperationConstruct construct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
        List<AdAdvertTestConfig> testConfigs = advTestConfigs.stream().filter(conf -> {
            //??????????????????
            if(conf.getType() == 0){
                return false;
            }
            String abTestId = String.valueOf(conf.getAb_test_id());
            String testId = String.valueOf(conf.getId());
            //???????????????????????????????????????????????????
            if(!testIdLst.stream().anyMatch(id -> id.equals(abTestId))){
                AdAdvertTestConfigVo testConfig = flowTestMapper.findAdTestConfigById(testId);
                flowTestMapper.deleteTestById(testId);
                flowTestMapper.deleteConfigByConfId(testId);
                List<AdTestCodeRelation> relations = flowTestMapper.findAdTestCodeRelationByConfigId(testId);
                construct.logDelete(TableInfoConstant.AD_ADVERT_TEST_CONFIG,testConfig);
                construct.logDelete(TableInfoConstant.AD_TEST_CODE_RELATION,relations);
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        for (int i = 0; i < testIdLst.size(); i++) {
            String testId = testIdLst.get(i);
            //????????????????????????????????????????????????
            int type = 0;
            if(i == 0){
                type = 1;
                //?????????????????????????????????????????????????????????????????????????????????
                if(CollectionUtils.isNotEmpty(testConfigs) &&
                        testConfigs.stream().anyMatch(conf ->
                                conf.getAb_test_id().equals(testId) && conf.getType() == FlowGroupConstant.TEST_GROUP_TYPE_TEST)){
                    //????????????
                    List<AdAdvertTestConfig> configs = flowTestMapper.findAdTestConfigByAbTestId(testId);
                    flowTestMapper.updateTestType(String.valueOf(type),testId);
                    construct.logUpdateList(TableInfoConstant.AD_ADVERT_TEST_CONFIG,TableInfoConstant.TYPE,configs,String.valueOf(type));
                }
            }else{
                type = 2;
                //??????????????????????????????????????????,??????????????????????????????????????????
                if(CollectionUtils.isNotEmpty(testConfigs) &&
                        testConfigs.stream().anyMatch(conf ->
                                conf.getAb_test_id().equals(testId) && conf.getType() == FlowGroupConstant.TEST_GROUP_TYPE_DUIZHAO)){
                    //????????????
                    List<AdAdvertTestConfig> configs = flowTestMapper.findAdTestConfigByAbTestId(testId);
                    flowTestMapper.updateTestType(String.valueOf(type),testId);
                    construct.logUpdateList(TableInfoConstant.AD_ADVERT_TEST_CONFIG,TableInfoConstant.TYPE,configs,String.valueOf(type));
                }
            }


            if(CollectionUtils.isEmpty(testConfigs) ||
                    testConfigs.stream().noneMatch(conf -> conf.getAb_test_id().equals(testId))){
                AdAdvertTestConfig config = new AdAdvertTestConfig(
                        null,flowId,testId,2,type,null,null,1);
                //????????????
                flowTestMapper.insertTestGroup(config);
                construct.logInsert(TableInfoConstant.AD_ADVERT_TEST_CONFIG,config);
            }
        }
        //?????????????????????
        //????????????
        AdAdvertFlowConfig config = flowTestMapper.findAdFlowConfigById(flowId);
        flowTestMapper.updateFlowTestState(flowId,testState);
        construct.logUpdate(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,TableInfoConstant.TEST_STATE,String.valueOf(config.getTest_state() == null ? "":config.getTest_state()),testState);
        construct.saveLog(LogOperationConstruct.PATH_SET_FLOW_GROUP,ip);

        //?????????????????????
        List<AdvCodeInfoVo> defaultPosCodeLstVos = getAdvCodeInfoVos(String.valueOf(adAdvertFlow.getPosition_id()));

        //??????????????????????????????
        ABFlowGroupVo abFlowGroupVo = returnFlowTest(flowConfigs.get(0), defaultPosCodeLstVos, abFlowMap, null);
        return ResultMap.success(abFlowGroupVo);
    }

    @Transactional
    @Override
    public void saveFlowTestGroup(List<TestInVo> testArr, String ip) {
        //??????????????????
        LogOperationConstruct construct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
        Set<String> allCodes = Sets.newHashSet();

        Set<Integer> allTest = Sets.newHashSet();
        List<AdAdvertTestConfigVo> adTestConfigList = Lists.newArrayList();

        Set<Integer> allFlow = Sets.newHashSet();
        Map<String,Integer> adFlowConfigMap = Maps.newHashMap();

        testArr.forEach(testArrVo -> {
            //?????????1-???????????????2-????????????
            Integer computer = testArrVo.getComputer();

            AdAdvertTestConfigVo adTestConfigBefore = flowTestMapper.findAdTestConfigById(String.valueOf(testArrVo.getId()));
            flowTestMapper.updateTestComputer(String.valueOf(testArrVo.getComputer()),String.valueOf(testArrVo.getId()));
            //????????????????????????????????????
            if(StringUtils.isNotEmpty(adTestConfigBefore.getFlowId())){
                String flowId = adTestConfigBefore.getFlowId();
                AdAdvertFlowConfigVo configById = flowTestMapper.findAdFlowConfigByIdVo(flowId);
                if(configById.getTestState() != null && configById.getTestState() == 1){
                    if(testArrVo.getOpenStatus() != null && testArrVo.getOpenStatus() == 1){
                        if(!allTest.contains(testArrVo.getId())){
                            allTest.add(testArrVo.getId());
                            adTestConfigBefore.setAbFirstLoadPosition(StringUtils.isEmpty(testArrVo.getAbFirstLoadPosition()) ? null : Integer.parseInt(testArrVo.getAbFirstLoadPosition()));
                            adTestConfigBefore.setAbSecondLoadPosition(StringUtils.isEmpty(testArrVo.getAbSecondLoadPosition() ) ? null : Integer.parseInt(testArrVo.getAbSecondLoadPosition()) );
                            adTestConfigBefore.setAbMaxShowNum(StringUtils.isEmpty(testArrVo.getAbMaxShowNum()) ? null : Integer.parseInt(testArrVo.getAbMaxShowNum()) );
                            adTestConfigBefore.setAbCustomRule1(StringUtils.isEmpty(testArrVo.getAbCustomRule1()) ? null :  testArrVo.getAbCustomRule1());
                            adTestConfigBefore.setAbCustomRule2(StringUtils.isEmpty(testArrVo.getAbCustomRule2()) ? null :  testArrVo.getAbCustomRule2());
                            adTestConfigBefore.setAbCustomRule3(StringUtils.isEmpty(testArrVo.getAbCustomRule3()) ? null :  testArrVo.getAbCustomRule3());
                            adTestConfigBefore.setLadderDelayMillis(StringUtils.isEmpty(testArrVo.getLadderDelayMillis()) ? "0" :  testArrVo.getLadderDelayMillis());
                            adTestConfigBefore.setCommonDelayMillis(StringUtils.isEmpty(testArrVo.getCommonDelayMillis()) ? "0" :  testArrVo.getCommonDelayMillis());
                            adTestConfigList.add(adTestConfigBefore);
                        }
                    }
                    if(!allFlow.contains(Integer.valueOf(flowId))){
                        allFlow.add(Integer.valueOf(flowId));
                        adFlowConfigMap.put(flowId,testArrVo.getOpenStatus());
                    }
                }
            }

            //??????????????????(????????????) start
            construct.logUpdate(TableInfoConstant.AD_ADVERT_TEST_CONFIG,TableInfoConstant.COMPUTER,String.valueOf(adTestConfigBefore.getComputer()),String.valueOf(testArrVo.getComputer()));
            //??????????????????(????????????) end

            //?????????????????????????????????????????????
            List<AdTestCodeRelation>  adTestCodeRelationList = flowTestMapper.getTestCodeRelaLst(String.valueOf(testArrVo.getId()),null);

            if(CollectionUtils.isNotEmpty(adTestCodeRelationList)){
                allCodes.addAll(adTestCodeRelationList.stream().map(adTestCodeRelation ->
                        String.valueOf(adTestCodeRelation.getCode_id())).collect(Collectors.toList()));
                //?????????????????????????????????
                List<AdTestCodeRelation> relationBefore = flowTestMapper.findAdTestCodeRelationByConfigId(String.valueOf(testArrVo.getId()));
                flowTestMapper.deleteConfigByConfId(String.valueOf(testArrVo.getId()));

                //??????????????????(????????????) start
                construct.logDeleteList(TableInfoConstant.AD_TEST_CODE_RELATION,relationBefore);
                //??????????????????(????????????) end
            }
            List<TestInVo.RelaArrVo> relaArr = testArrVo.getRelaArr();
            if(CollectionUtils.isNotEmpty(relaArr)){
                relaArr.forEach(relaVo -> {
                    AdTestCodeRelation rela = new AdTestCodeRelation(null,String.valueOf(testArrVo.getId()), relaVo.getCode_id(),
                                Integer.parseInt(relaVo.getNumber()), Integer.parseInt(relaVo.getMatching()),Integer.parseInt(relaVo.getOrder()), null,null,1);
                    allCodes.add(String.valueOf(relaVo.getCode_id()));
                    flowTestMapper.insertRelaGroup(rela);

                    //??????????????????(????????????) start
                    construct.logInsert(TableInfoConstant.AD_TEST_CODE_RELATION,rela);
                    //??????????????????(????????????) end
                });
            }
        });

        //????????????????????????,?????????????????????
        if(CollectionUtils.isNotEmpty(adTestConfigList)){
            flowTestMapper.updateBatchTestInfo(adTestConfigList);
        }
        if(MapUtils.isNotEmpty(adFlowConfigMap)){
            flowTestMapper.updateBatchOpenStatusById(adFlowConfigMap);
        }

        //?????????????????????????????????????????????????????????????????????????????????????????????
        List<Map> codeRelations = flowTestMapper.findTestCodeRelaByCodeId(new ArrayList<>(allCodes));
        Map<String,String> dataMap = Maps.newHashMap();
        for (String code : allCodes) {
            //????????????
            if(codeRelations.stream().anyMatch(codeRela -> String.valueOf(codeRela.get("code_id")).equals(code))){
                dataMap.put(code,"1");
            }else{
                dataMap.put(code,"2");
            }
        }

        List<Map<String,Object>> advertCodesMap = flowTestMapper.findAdCodeByIds(dataMap);
        flowTestMapper.updateCodePutInByDataMap(dataMap);

        //??????????????????
        construct.logUpdateMap(TableInfoConstant.AD_ADVERT_CODE,TableInfoConstant.PUT_IN,advertCodesMap,dataMap);

        construct.saveLog(LogOperationConstruct.PATH_SAVE_SET,ip);
    }

    /**
     * ??????????????????????????????????????????
     * @param adAdvertTestConfigs
     * @param defaultPosCodeLstVos
     * @param testArr
     * @return
     */
    private List<ABTestGroupVo> getAdvTestConfs(List<AdAdvertTestConfig> adAdvertTestConfigs, List<AdvCodeInfoVo> defaultPosCodeLstVos, List<Map<String,Object>> testArr) {

        List<ABTestGroupVo> abTestGroupVoList = Lists.newArrayList();
        if(CollectionUtils.isEmpty(adAdvertTestConfigs)){
            return abTestGroupVoList;
        }

        for (int i = 0; i < adAdvertTestConfigs.size(); i++) {
            AdAdvertTestConfig conf = adAdvertTestConfigs.get(i);
            String configId = String.valueOf(conf.getId());
            //????????????
            int computer = conf.getComputer();

            //??????????????????????????????????????????????????????
            List<AdTestCodeRelation> codeRelations = flowTestMapper.getTestCodeRelaLst(configId,computer);

            List<AdvCodeInfoVo> posCodeLstVos = Lists.newArrayList();
            if(CollectionUtils.isNotEmpty(codeRelations)) {
                codeRelations.stream().forEach(codeRela -> {
                    Optional<AdvCodeInfoVo> advCodeInfoVoOptional = defaultPosCodeLstVos.stream().filter(codeLstVo ->
                            codeLstVo.getId().equals(codeRela.getCode_id())).findFirst();
                    if (advCodeInfoVoOptional.isPresent()) {
                        AdvCodeInfoVo advCodeInfoVo = advCodeInfoVoOptional.get();
                        advCodeInfoVo.setNumber(codeRela.getNumber());
                        advCodeInfoVo.setMatching(codeRela.getMatching());
                        advCodeInfoVo.setOrderNum(codeRela.getOrder_num() != null? codeRela.getOrder_num() : 0);
                        posCodeLstVos.add(advCodeInfoVoOptional.get());
                    }
                });
            }
            //????????????,???????????????????????????
            if(conf.getComputer() == FlowGroupConstant.HANDLER_MATCHING){
                posCodeLstVos.sort(Comparator.comparing(AdvCodeInfoVo::getNumber));
            }else{
                posCodeLstVos.sort(Comparator.comparing(AdvCodeInfoVo::getOrderNum).reversed());
            }
            ABTestGroupVo testInfoVo = new ABTestGroupVo(
                    conf.getId(), conf.getAb_test_id(), null, conf.getComputer(), conf.getType(), posCodeLstVos,null,conf.getAbFirstLoadPosition(),conf.getAbSecondLoadPosition(),
                    conf.getAbMaxShowNum(),conf.getAbCustomRule1(),conf.getAbCustomRule2(),conf.getAbCustomRule3(),conf.getLadderDelayMillis(),conf.getCommonDelayMillis());
            if(StringUtils.isNotEmpty(conf.getAb_test_id()) && CollectionUtils.isNotEmpty(testArr)){
                Optional<Map<String,Object>> mapOptional =
                        testArr.stream().filter(test -> conf.getAb_test_id().equals(String.valueOf(test.get("id")))).findFirst();
                if(mapOptional.isPresent()){
                    testInfoVo.setAb_test_id(conf.getAb_test_id());
                    testInfoVo.setPercentage(String.valueOf((double)(
                            (Integer)mapOptional.get().get("traffic"))/10));
                    testInfoVo.setName(mapOptional.get().get("name").toString());
                }
            }
            abTestGroupVoList.add(testInfoVo);
        }
        return abTestGroupVoList;
    }

    /**
     * ????????????????????????????????????????????????
     * @param defaultPosCodeLstVos
     * @param channelIds
     * @param version1
     * @param version2
     * @param versionFlag
     * @return
     */
    private List<AdvCodeInfoVo> getAdvCodesByFilter(List<AdvCodeInfoVo> defaultPosCodeLstVos,
                                                    String channelIds, String channel2Ids, String version1, String version2, String version3, String version4, String versionFlag) {
        List<AdvCodeInfoVo> newAdvCodes = Lists.newArrayList();
        //???????????????
        boolean versionEques = true;
        if(StringUtils.isNotEmpty(versionFlag) && versionFlag.indexOf("??????") < 0){
            versionEques = false;
        }
        for (int j = 0; j < defaultPosCodeLstVos.size(); j++) {
            AdvCodeInfoVo vo = defaultPosCodeLstVos.get(j);

            boolean chaneFlag = false;

            //??????AB????????? ?????? ?????????????????????
            if( (StringUtil.isEmpty(channelIds) && StringUtil.isEmpty(channel2Ids) ) || FlowGroupConstant.CHANNEL_TYPE_ALL == vo.getChannel_type()){
                chaneFlag = true;
            } else if(FlowGroupConstant.CHANNEL_TYPE_EX == vo.getChannel_type() && "-1".equals(vo.getChannel_ids())){
                chaneFlag = false;
            } else if(FlowGroupConstant.CHANNEL_TYPE_ONLY == vo.getChannel_type() && "-1".equals(vo.getChannel_ids())){
                chaneFlag = true;
            } else {
                if(StringUtil.isNotEmpty(channelIds)){
                    //????????????
                    List<String> channelLst = Arrays.asList(channelIds.split(","));
                    for (int z = 0; z < channelLst.size(); z++) {
                        if(vo.getChannel_ids().indexOf(channelLst.get(z)) > -1 && FlowGroupConstant.CHANNEL_TYPE_ONLY == vo.getChannel_type()
                                || vo.getChannel_ids().indexOf(channelLst.get(z)) == -1 && FlowGroupConstant.CHANNEL_TYPE_EX == vo.getChannel_type()){
                            chaneFlag = true;
                            break;
                        }
                    }
                }
                if(StringUtil.isNotEmpty(channel2Ids)){
                    //????????????
                    String[] csp2 = channel2Ids.split(",");
                    List<String> codeIdslLst = Arrays.asList(vo.getChannel_ids().split(","));
                    if(codeIdslLst.size() > csp2.length){
                        chaneFlag = true;
                    }
                    for (int z = 0; z < codeIdslLst.size(); z++) {
                        if(channel2Ids.indexOf(codeIdslLst.get(z)) == -1 && FlowGroupConstant.CHANNEL_TYPE_ONLY == vo.getChannel_type()){
                            chaneFlag = true;
                            break;
                        }
                    }
                }
            }

            //???????????????
            boolean versionFlag1 = false;
            boolean versionFlag2 = false;
            boolean versionFlag3 = false;
            boolean versionFlag4 = false;
            //??????????????? ???????????????
            if(StringUtils.isEmpty(version1) && StringUtils.isEmpty(version2)){
                versionFlag1 = true;
                versionFlag2 = true;
            }else{
                if(StringUtils.isNotEmpty(version1) &&
                        (versionEques?VersionUtil.compare(vo.getVersion2(),version1):VersionUtil.compareIsHigh(vo.getVersion2(),version1))){
                    if(StringUtils.isEmpty(version2) || (versionEques?VersionUtil.compare(version2, vo.getVersion1()) : VersionUtil.compareIsHigh(version2, vo.getVersion1())) ){
                        versionFlag1 = true;
                    }
                }
                if(StringUtils.isNotEmpty(version2) &&
                        (versionEques?VersionUtil.compare(version2,vo.getVersion1()):VersionUtil.compareIsHigh(version2,vo.getVersion1()))){
                    if(StringUtils.isEmpty(version1) || (versionEques?VersionUtil.compare(vo.getVersion2(), version1):VersionUtil.compareIsHigh(vo.getVersion2(), version1))){
                        versionFlag2 = true;
                    }
                }
            }

            if(StringUtils.isEmpty(version3) && StringUtils.isEmpty(version4)){
                versionFlag3 = true;
                versionFlag4 = true;
            }else{
                if(StringUtils.isNotEmpty(version3) ){
                    List<String> versionList = Arrays.asList(version3.split(","));
                    for (String version:versionList) {
                        //??????????????????1?????????version???????????????2
                        if(StringUtils.isEmpty(vo.getVersion1())){
                            if(VersionUtil.compare(vo.getVersion2(),version)){
                                versionFlag3 = true;
                                break;
                            }
                        } else if (StringUtils.isEmpty(vo.getVersion2()) ){
                            if(VersionUtil.compare(version,vo.getVersion1())){
                                versionFlag3 = true;
                                break;
                            }
                        } else if(VersionUtil.compare(version,vo.getVersion1()) && VersionUtil.compare(vo.getVersion2(),version)){
                            versionFlag3 = true;
                            break;
                        }
                    }
                }
                //version4 ????????????  ????????????????????????????????????  1.4.6,1.5.7,2.0.0 ???????????? ???version1 ???version2???????????? [version1,version2] ????????????. ?????????????????????????????????????????????
                if(StringUtils.isNotEmpty(version4) ){
                    boolean flag = true ;
                    if(StringUtils.isNotEmpty(vo.getVersion1()) && StringUtils.isNotEmpty(vo.getVersion2()) && vo.getVersion1().equals(vo.getVersion2())){
                        List<String> versionList = Arrays.asList(version4.split(","));
                        for (String version:versionList) {
                            if(vo.getVersion1().equals(version) && vo.getVersion2().equals(version)){
                                flag = false;
                                break;
                            }
                        }
                    }
                    if(flag){
                        versionFlag4 = true;
                    }
                }
            }

            if(chaneFlag && (versionFlag1 || versionFlag2) && (versionFlag3 || versionFlag4)){
                newAdvCodes.add(vo);
            }

        }

        return newAdvCodes;
    }

    /**
     * ?????????????????????
     *
     * @param positionId
     * @param packageName
     * @param channelId
     * @param userType
     * @param city
     * @return
     */
    public List<AdvCodeInfoVo> getAdOrder(String positionId, String packageName, String channelId, int userType, String city) {

        List<AdvCodeInfoVo> list = Lists.newArrayList();

        List<HashMap<String, Object>> ladderAdList = new ArrayList<HashMap<String, Object>>();
        List<HashMap<String, Object>> commonAdList = new ArrayList<HashMap<String, Object>>();

        List<AdvCodeInfoVo> advCodeListVos = getAdvCodeInfoVos(positionId);
        Map<String, AdvCodeInfoVo> advCodeMapInfo = new HashMap<>(); //????????????????????????
        List<String> adIds = advCodeListVos.stream().map(AdvCodeInfoVo::getAd_id).collect(Collectors.toList()); //?????????id??????
        Map<String, Object> adEcpm = this.countMultiEcpm(adIds, packageName, channelId, userType, city);  //????????????????????????????????????????????????????????????ecpm

        for (AdvCodeInfoVo vo : advCodeListVos) {

            int channelType = vo.getChannel_type();
            String channelIds = vo.getChannel_ids();

            //????????????
            if (StringUtils.isNotEmpty(channelId) && !channelId.equals("-1") && StringUtils.isNotEmpty(channelIds)) {
                String[] channelIdStr = channelIds.split(",");
                List<String> channelIdList = Arrays.asList(channelIdStr);
                if (channelType ==  FlowGroupConstant.VERSION_TYPE_ONLY) {
                    if (!channelIdList.contains(channelId)) {
                        continue;
                    }
                } else if (channelType ==  FlowGroupConstant.VERSION_TYPE_EX) {
                    if (channelIdList.contains(channelId)) {
                        continue;
                    }
                }

            }

            String adId = vo.getAd_id(); //?????????id
            Integer ladderType = vo.getLadder(); //????????????
            String landerPrice = vo.getLadder_price(); //?????????
            Integer id = vo.getId();
            advCodeMapInfo.put(adId, vo);
            if (ladderType == FlowGroupConstant.AD_COMMON_TYPE) {
                double ecpm = (adEcpm.get(adId) == null ? 0D : Double.parseDouble(String.valueOf(adEcpm.get(adId))));
                HashMap<String, Object> adMap = new HashMap<String, Object>();
                adMap.put("adId", adId);
                adMap.put("ecpm", ecpm);
                adMap.put("id", id);
                commonAdList.add(adMap);

            } else {
                HashMap<String, Object> adMap = new HashMap<String, Object>();
                adMap.put("adId", adId);
                adMap.put("landerPrice", landerPrice);
                adMap.put("id", id);
                ladderAdList.add(adMap);
            }
        }

        if (ladderAdList.size() > 0) {
            Collections.sort(ladderAdList, new Comparator<HashMap<String, Object>>() {
                public int compare(HashMap<String, Object> m1, HashMap<String, Object> m2) {
                    double landerPrice1 = Double.parseDouble((String)m1.get("landerPrice"));
                    double landerPrice2 = Double.parseDouble((String)m2.get("landerPrice"));
                    double landerPrice = landerPrice2 - landerPrice1;
                    if (landerPrice != 0) {
                        return landerPrice > 0 ? 1 : -1;
                    }
                    Integer id1 = (Integer) m1.get("id");
                    Integer id2 = (Integer) m2.get("id");
                    Integer id = id2 - id1;
                    return id > 0 ? 1 : -1;

                }
            });
            for (HashMap<String, Object> ladderAdMap : ladderAdList) {
                String adId = (String)ladderAdMap.get("adId");
                AdvCodeInfoVo adCodeInfo = advCodeMapInfo.get(adId);
                list.add(adCodeInfo);
            }
        }

        if (commonAdList.size() > 0) {
            Collections.sort(commonAdList, new Comparator<HashMap<String, Object>>() {
                public int compare(HashMap<String, Object> m1, HashMap<String, Object> m2) {
                    double ecpm1 = (double)m1.get("ecpm");
                    double ecpm2 = (double)m2.get("ecpm");
                    double ecpm = ecpm1 - ecpm2;
                    if (ecpm != 0) {
                        return ecpm > 0 ? -1 : 1;
                    }
                    Integer id1 = (Integer) m1.get("id");
                    Integer id2 = (Integer) m2.get("id");
                    Integer id = id2 - id1;
                    return id > 0 ? -1 : 1;
                }
            });

            for (HashMap<String, Object> commonAdMap : commonAdList) {
                String adId = (String)commonAdMap.get("adId");
                Double ecpm = (double)commonAdMap.get("ecpm");
                AdvCodeInfoVo adCodeInfo = advCodeMapInfo.get(adId);
                adCodeInfo.setEcpm(ecpm);
                list.add(adCodeInfo);
            }
        }
        return list;
    }

    /**
     * ???????????????????????????????????????????????????????????????ecpm
     * @param adIdList ?????????id??????
     * @param packageName ??????
     * @param channelId ??????
     * @param userType ??????????????????
     * @param city ??????,???????????????
     * @return
     */
    private Map<String, Object> countMultiEcpm(List<String> adIdList, String packageName, String channelId, int userType, String city) {
        String adIds = String.join(",", adIdList);
        String url = countMultiEcpmUrl + "?adIds={0}&isNew={1}&city={2}&channel={3}&appPackage={4}";
        url = MessageFormat.format(url, adIds, userType, city, channelId, packageName);
        try {
            String resultJson = restTemplate.postForObject(url, null, String.class);  //??????????????????????????????
            return JSONObject.parseObject(resultJson, Map.class);
        } catch (Exception e) {
            log.error("???????????????????????????????????????????????????????????????ecpm?????????", e);
            return new HashMap<>();
        }
    }

    /**
     * ???????????????ecpm
     *
     * @param profit
     * @param adId
     * @param packageName
     * @param channelId
     * @param userType
     * @param city
     * @param date
     * @return
     */
    public double getAdIdEcpm(double profit, String adId, String packageName, String channelId, int userType, String city, String date) {
        if (profit <= 0) {
            return 0;
        }
        double ecpm = 0;
        AdMultiDimenVo adReportAllData = adMultiDimenService.getAdReport(adId, packageName, "-1", -1, "-1", date);
        int validClick = 0;
        if (adReportAllData != null) {
            validClick = adReportAllData.getValidClick();
        }
        AdMultiDimenVo adReportData = adMultiDimenService.getAdReport(adId, packageName, channelId, userType, city, date);
        if (adReportData != null) {
            int show = adReportData.getShow();
            int click = adReportData.getClick();
            if (show > 0 && click > 0 && validClick > 0) {
                double price = profit / validClick;
                ecpm = 1000 * ((click * price) / show);
            }
        }
        return ecpm;
    }

}
