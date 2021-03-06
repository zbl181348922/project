package com.miguan.advert.domain.serviceimpl;

import cn.jiguang.common.utils.StringUtils;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.miguan.advert.common.abplat.ABPlatFormService;
import com.miguan.advert.common.constants.FlowGroupConstant;
import com.miguan.advert.common.constants.TableInfoConstant;
import com.miguan.advert.common.constants.VersionConstant;
import com.miguan.advert.common.construct.LogOperationConstruct;
import com.miguan.advert.common.exception.ServiceException;
import com.miguan.advert.common.task.AbExpTask;
import com.miguan.advert.common.util.*;
import com.miguan.advert.common.util.redis.RedisService;
import com.miguan.advert.config.redis.util.RedisKeyConstant;
import com.miguan.advert.domain.mapper.AdOperationLogMapper;
import com.miguan.advert.domain.mapper.AdPlatMapper;
import com.miguan.advert.domain.mapper.FlowTestMapper;
import com.miguan.advert.domain.pojo.AdAdvertCode;
import com.miguan.advert.domain.pojo.AdAdvertFlowConfig;
import com.miguan.advert.domain.pojo.AdAdvertTestConfig;
import com.miguan.advert.domain.pojo.AdTestCodeRelation;
import com.miguan.advert.domain.service.AbFlowGroupService;
import com.miguan.advert.domain.service.TableInfoService;
import com.miguan.advert.domain.service.ToolMofangService;
import com.miguan.advert.domain.vo.ChannelInfoVo;
import com.miguan.advert.domain.vo.interactive.*;
import com.miguan.advert.domain.vo.request.AbFlowGroupParam;
import com.miguan.advert.domain.vo.result.ABFlowGroupVo;
import com.miguan.advert.domain.vo.result.ABTestGroupVo;
import com.miguan.advert.domain.vo.result.AdvCodeInfoVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @program: advert-java
 * @description: ab??????
 * @author: kangkh
 * @create: 2020-10-27 10:36
 **/
@Slf4j
@Service
public class AbFlowGroupServiceImpl implements AbFlowGroupService {


    @Resource
    private RedisService redisService;

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
    private AbExpTask abExpTask;

    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public Integer searchAppId(String app_type) throws ServiceException {
        String appid = redisService.get(RedisKeyConstant.CHANNAL_SEARCH_APPID + app_type);
        if(StringUtils.isEmpty(appid)){
            Integer appValue = null;
            try {
                appValue = toolMofangService.searchAppId(app_type);
            } catch ( EmptyResultDataAccessException e){
                throw new ServiceException("?????????app_type???channel_group?????????????????????????????????app_id");
            }
            if(appValue != null){
                redisService.set(RedisKeyConstant.CHANNAL_SEARCH_APPID + app_type,appValue,RedisKeyConstant.CHANNAL_SEARCH_APPID_SECONDS);
                appid = appValue + "";
            }
        }
        if(appid == null){
            throw new ServiceException("?????????app_type???channel_group?????????????????????????????????app_id");
        }
        return Integer.parseInt(appid);
    }

    private String login() throws ServiceException{
        String token = redisService.get(RedisKeyConstant.AB_TEST_LOGIN_TOKEN);
        if(StringUtils.isEmpty(token)){
            AbResultMap resultMap = abPlatFormService.login();
            if(resultMap.getCode() == -1){
                throw new ServiceException("????????????,????????????????????????????????????");
            } else if (resultMap.getCode() != 0) {
                throw new ServiceException("?????????,AB???????????????????????????????????????");
            }
            Map<String,Object> acr = (Map<String,Object>)resultMap.getData();
            token = MapUtils.getString(acr, "token");
            if(StringUtils.isNotEmpty(token)){
                redisService.set(RedisKeyConstant.AB_TEST_LOGIN_TOKEN,token,RedisKeyConstant.AB_TEST_LOGIN_TOKEN_SECONDS);
            }
        }
        return token;
    }

    @Override
    public List<AppInfo> appList() throws ServiceException{
        String appList = redisService.get(RedisKeyConstant.AB_APP_LIST_TOKEN);
        if(StringUtils.isEmpty(appList)){
            //1?????????
            String token = login();
            if(StringUtils.isEmpty(token)){
                throw new ServiceException("????????????,?????????token??????");
            }
            AbResultMap resultMap = abPlatFormService.appList(token);
            if(resultMap.getCode() != 0){
                if(resultMap.getCode() == 4001 || resultMap.getCode() == 4005){
                    throw new ServiceException(resultMap.getMsg());
                }
                throw new ServiceException("????????????????????????Ab???????????????code"+resultMap.getCode());
            }
            List<AppInfo> result = (List<AppInfo>)resultMap.getData();
            if(CollectionUtils.isNotEmpty(result)){
                String jsonStr = JSONObject.toJSONString(result);
                redisService.set(RedisKeyConstant.AB_APP_LIST_TOKEN,jsonStr,RedisKeyConstant.AB_APP_LIST_TOKEN_SECONDS);
            }
            return result;
        } else {
            List<AppInfo> result = JSONObject.parseObject(appList,List.class);
            return result;
        }
    }


    private List<Integer> getAdTag(Integer app_id, String token) throws ServiceException{
        String adTag = redisService.get(RedisKeyConstant.AB_TEST_AD_TAG);
        List<Integer> tagIds = Lists.newArrayList();
        log.error("token:"+token);
        if(StringUtils.isEmpty(adTag)){
            AbResultMap resultMap = abPlatFormService.getTags(app_id,token);
            if(resultMap.getCode() != 0){
                if(resultMap.getCode() == 4001){
                    throw new ServiceException(resultMap.getMsg());
                }
                throw new ServiceException("???????????????,AB???????????????????????????????????????"+ resultMap.getMsg());
            }
            List<Map<String,Object>> tags = (List<Map<String,Object>>)resultMap.getData();
            if(CollectionUtils.isEmpty(tags)){
                throw new ServiceException("????????????????????????AB????????????????????????");
            }
            tags.forEach(tag -> {
                String name = MapUtils.getString(tag,"name");
                if(name.contains("??????")){
                    Integer id = MapUtils.getInteger(tag, "id");
                    tagIds.add(id);
                }
            });
            if(CollectionUtils.isNotEmpty(tagIds)){
                adTag = StringUtil.toString(tagIds,",");
                redisService.set(RedisKeyConstant.AB_TEST_AD_TAG,adTag,RedisKeyConstant.AB_TEST_AD_TAG_SECONDS);
            }
            return tagIds;
        } else {
            List<Integer> tags = StringUtil.strToIntegerList(adTag);
            return tags;
        }
    }

    @Override
    public ResultMap saveFlowInfo(AbFlowGroupParam abFlowGroupParam, String flowId, String ip) throws ServiceException {
        //??????app??????
        Integer appId = searchAppId(abFlowGroupParam.getApp_type());
        abFlowGroupParam.fillAppId(appId);
        //1?????????
        String token = login();
        if (StringUtils.isEmpty(token)) {
            throw new ServiceException("?????????,??????token?????????");
        }
        //??????code
        if(StringUtils.isEmpty(abFlowGroupParam.getCode())){
            List<String> expCode = flowTestMapper.findAllCodeByPositionId(abFlowGroupParam.getPosition_id());
            String code = "ad_exp_"+abFlowGroupParam.getPosition_id()+"_0";
            if(CollectionUtils.isNotEmpty(expCode)){
                code = getMaxCode(expCode,abFlowGroupParam.getPosition_id());
            }
            abFlowGroupParam.setCode(code);
            abFlowGroupParam.getAbExperiment().setCode(code);
        }

        //???????????????????????????????????????
        AdAdvertFlowConfig flowConfig = null;

        boolean flag = false;  //true??????????????? false???????????????
        int state = 0;
        //??? flowId ??????  experiment_id??????
        if (StringUtils.isEmpty(flowId) && abFlowGroupParam.getExperiment_id() == null) {
            flag = true;
        } else if (abFlowGroupParam.getExperiment_id() == null){
            List<AdAdvertFlowConfig> flowConfigs = flowTestMapper.getAdvFlowConfLst(null, null, flowId, null);
            flowConfig = flowConfigs.get(0);
            if(StringUtils.isNotEmpty(flowConfig.getAb_flow_id()) && abFlowGroupParam.getExperiment_id() == null){
                abFlowGroupParam.getAbExperiment().setId(Integer.parseInt(flowConfig.getAb_flow_id()));
                abFlowGroupParam.setExperiment_id(Integer.parseInt(flowConfig.getAb_flow_id()));
            }
        } else {
            //??????????????????  experiment_id ?????????,flowId ??????.?????????????????????
            List<AdAdvertFlowConfig> flowConfigs = flowTestMapper.getAdvFlowConfLst(null, null, null, abFlowGroupParam.getExperiment_id().toString());
            flowConfig = flowConfigs.get(0);
            flowId = flowConfig.getId().toString();
        }
        if(flowConfig != null){
            AbExperiment abExperiment = getExperimentInfo(flowConfig.getAb_flow_id(),appId,token);
            state = abExperiment.getState();
            abFlowGroupParam.getAbExperiment().setCode(abExperiment.getCode());
            //?????????????????????
            if(state != 0){ //??????????????????0 ???,????????? ???????????? ,????????????, ???????????????
                if(abFlowGroupParam.getState_bak() != null  && abFlowGroupParam.getState_bak() == 0){
                    return ResultMap.success("???????????????,????????????","???????????????????????????????????????????????????");  //???????????? ??????????????????
                }
                abExperiment.setDoc_url(abFlowGroupParam.getDoc_url());
                abExperiment.setName(abFlowGroupParam.getName());
                abFlowGroupParam.setAbExperiment(abExperiment);
            }
        }
        String vmsg = "";
        if(state == 0){
            try {
                vmsg = checkStateInfo(abFlowGroupParam);
            } catch (ParseException e) {
                throw new ServiceException("?????????????????????");
            }
        }
        if(StringUtils.isNotEmpty(vmsg)){
            throw new ServiceException(vmsg);
        }

        //1?????????????????????
        Integer olayerId = abFlowGroupParam.getAbLayer().getId();

        Integer layerId = null;
        //double traffic = 0;
        if (olayerId == null || olayerId == -1) {
            //??????????????????
            layerId = saveLayer(abFlowGroupParam.getAbLayer(), token);
        } else {
            //???????????????,??????????????????????????????
            layerId = abFlowGroupParam.getAbLayer().getId();

            //??????????????? (??????????????????????????????);  ????????????????????????
            //traffic = getLayerTrafficById(olayerId,token);
        }
        if (layerId == null) {
            throw new ServiceException("????????????,ab???????????????????????????");
        }

        //2?????????????????????
        abFlowGroupParam.getAbExperiment().setLayer_id(layerId);
        Integer expId = saveExperiment(abFlowGroupParam.getAbExperiment(), token);

        //3?????????????????????
        abFlowGroupParam.getAbFlowDistribution().setExp_id(expId);
        List<Integer> flowDistributions = saveFlowDistribution(abFlowGroupParam.getAbFlowDistribution(), token);


        //??????????????????0.???????????? ????????????????????????,??????????????? //??????????????????
        if(state != 0){
            //??????????????????
            flowTestMapper.updateName(abFlowGroupParam.getName(),flowId,state);
            return ResultMap.success();
        }

        //??????????????????
        Integer positionId = abFlowGroupParam.getPosition_id();

        if(flag){
            flowConfig = addFlowInfo(abFlowGroupParam, expId, flowDistributions, positionId);
        } else {
            updateFlowInfo(flowId, flowDistributions,abFlowGroupParam);
        }

        //????????????????????????
        log.info("===????????????????????????===");
        //4?????????????????????
        //??????????????????
        Map<String, Object> map = Maps.newHashMap();
        map.put("app_id", abFlowGroupParam.getApp_id());
        map.put("experiment_id", expId);
        map.put("state", 1); //?????????????????????abFlowGroupParam.state == 1 ??? ????????????. ???????????????????????????>??????????????????????????????
        try {
            if (abFlowGroupParam.getState() != null && abFlowGroupParam.getState() == 1) {
                //????????????
                sendEditState(map, token);
                //??????????????????????????????????????????
                abExpTask.deleteTask(expId);
            } else if (StringUtils.isNotEmpty(abFlowGroupParam.getPub_time()) && sdf.parse(abFlowGroupParam.getPub_time()).compareTo(new Date()) > 0 ) {
                //????????????
                abExpTask.pubAbExp(map, expId, abFlowGroupParam.getPub_time());
            } else {
                //??????????????????????????????????????????
                abExpTask.deleteTask(expId);
            }
        } catch (ParseException e){
            log.error("????????????,???????????????????????????????????????");
            throw new ServiceException("?????????????????????????????????");
        }

        //????????????
        //???????????????????????????
//        ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(expId + "");
//        if(resultMap.getCode() != 200){
//            return ResultMap.error(resultMap.getMessage());
//        }
//        Map<String, Object> abFlowMap =resultMap.getData();
//
//        //?????????????????????
//        List<AdvCodeInfoVo> defaultPosCodeLstVos = getAdvCodeInfoVos(String.valueOf(positionId));
//
//        //??????????????????????????????
//        ABFlowGroupVo abFlowGroupVo = returnFlowTest(flowConfig, defaultPosCodeLstVos, abFlowMap);
        return ResultMap.success();
    }

    private String getMaxCode(List<String> expCode,Integer positionId) {
        int codeId = 0;
        for (String code:expCode) {
            String codeStr = code.replace("ad_exp_" + positionId + "_","");
            int codeTmp = StringUtils.isEmpty(codeStr)? 0 : Integer.parseInt(codeStr);
            if(codeTmp > codeId){
                codeId = codeTmp;
            }
        }
        return "ad_exp_"+positionId+"_"+(codeId+1);
    }

    private AbExperiment getExperimentInfo(String exp_id, Integer appId, String token) throws ServiceException{
        AbResultMap resultMap = abPlatFormService.getExperimentInfo(exp_id,appId,token);
        if (resultMap.getCode() != 0) {
            throw new ServiceException(resultMap.getMsg());
        }
        try{
            AbExperiment abExperiment = new AbExperiment();
            Map<String,Object> source = (Map<String,Object>)resultMap.getData();
            BeanUtils.copyProperties(abExperiment,source);
            fillExisAbInfo(abExperiment,source);
            return abExperiment;
        } catch (Exception e){
            throw new ServiceException("??????????????????:"+e.getMessage());
        }
    }

    private void fillExisAbInfo(AbExperiment abExperiment, Map<String, Object> source) throws ParseException {
        abExperiment.setVersion_list(abExperiment.getGroup_list()); //?????????
        if(abExperiment.getPeriod() == null){
            abExperiment.setPeriod(Lists.newArrayList());
        }
        SimpleDateFormat sdfn = new SimpleDateFormat("yyyy-MM-dd");
        abExperiment.getPeriod().add(sdfn.parse(source.get("start_date").toString()));
        abExperiment.getPeriod().add(sdfn.parse(source.get("end_date").toString()));
    }

    private String checkStateInfo(AbFlowGroupParam abFlowGroupParam) throws ParseException {
        String pubTime = abFlowGroupParam.getPub_time();
        if(StringUtils.isNotEmpty(pubTime) && sdf.parse(pubTime).compareTo(new Date()) <= 0){
            return "???????????????????????????????????????";
        }
        return null;
    }

    private double getLayerTrafficById(Integer olayerId, String token) throws  ServiceException{
        AbResultMap resultMap = abPlatFormService.getLayerTrafficById(olayerId,token);
        if (resultMap.getCode() != 0) {
            throw new ServiceException(resultMap.getMsg());
        }
        JSONObject layerInfo = (JSONObject)JSONObject.toJSON(resultMap.getData());
        Object trafficObj = layerInfo.get("traffic");
        if(trafficObj instanceof Integer ){
            return Double.valueOf((Integer) layerInfo.get("traffic"));
        } else {
            return (double)layerInfo.get("traffic");
        }
    }

    private void updateFlowInfo(String flowId, List<Integer> flowDistributions, AbFlowGroupParam abFlowGroupParam) {
        //?????????????????????????????????
        List<AdAdvertTestConfig> advTestConfigs = flowTestMapper.getAdvTestConfLst(flowId,null);

        //??????????????????
        flowTestMapper.updateInfo(abFlowGroupParam.getName(),abFlowGroupParam.getPub_time(),flowId,abFlowGroupParam.getState());

        List<AdAdvertTestConfig> testConfigs = advTestConfigs.stream().filter(conf -> {
            //??????????????????
            if(conf.getType() == 0){
                return false;
            }
            String abTestId = String.valueOf(conf.getAb_test_id());
            String testId = String.valueOf(conf.getId());
            //???????????????????????????????????????????????????
            if(!flowDistributions.stream().anyMatch(id -> (id+"").equals(abTestId))){
                flowTestMapper.deleteTestById(testId);
                flowTestMapper.deleteConfigByConfId(testId);
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        for (int i = 0; i < flowDistributions.size(); i++) {
            String testId = flowDistributions.get(i)+"";
            //????????????????????????????????????????????????
            int type = 0;
            if(i == 0){
                type = 1;
                //?????????????????????????????????????????????????????????????????????????????????
                if(CollectionUtils.isNotEmpty(testConfigs) &&
                        testConfigs.stream().anyMatch(conf ->
                                conf.getAb_test_id().equals(testId) && conf.getType() == FlowGroupConstant.TEST_GROUP_TYPE_TEST)){
                    flowTestMapper.updateTestType(String.valueOf(type),testId);
                }
            }else{
                type = 2;
                //??????????????????????????????????????????,??????????????????????????????????????????
                if(CollectionUtils.isNotEmpty(testConfigs) &&
                        testConfigs.stream().anyMatch(conf ->
                                conf.getAb_test_id().equals(testId) && conf.getType() == FlowGroupConstant.TEST_GROUP_TYPE_DUIZHAO)){
                    flowTestMapper.updateTestType(String.valueOf(type),testId);
                }
            }
            if(CollectionUtils.isEmpty(testConfigs) ||
                    testConfigs.stream().noneMatch(conf -> conf.getAb_test_id().equals(testId))){
                AdAdvertTestConfig config = new AdAdvertTestConfig(
                        null,flowId,testId,2,type,null,null,1);
                flowTestMapper.insertTestGroup(config);
            }
        }
        flowTestMapper.updateFlowTestState(flowId,"1");
    }

    private AdAdvertFlowConfig addFlowInfo(AbFlowGroupParam abFlowGroupParam, Integer expId, List<Integer> flowDistributions, Integer positionId) {
        AdAdvertFlowConfig flowConfig;//????????????
        //??????????????????
        String name = FlowGroupConstant.AB_FLOW_GROUP_NAME;
        if(StringUtils.isNotEmpty(abFlowGroupParam.getName())){
            name = abFlowGroupParam.getName();
        }
        flowConfig =
                new AdAdvertFlowConfig(null, name,
                        positionId,expId + "",2,1,null,null,1,abFlowGroupParam.getAbExperiment().getCode(),abFlowGroupParam.getPub_time(),abFlowGroupParam.getState());
        flowTestMapper.insertFlowGroup(flowConfig);
        AdAdvertTestConfig adAdvertTestConfig = flowTestMapper.getDefaultAdvTestConf(positionId);
        List<AdAdvertTestConfig> testConfigs = Lists.newArrayList();
        //??????????????????
        for (int i = 0 ; i < flowDistributions.size() ; i ++) {
            int flowDist = flowDistributions.get(i);
            int type = 2;
            if(i == 0){
                //???????????????
                type = 1;
            }  else {
                type = 2;
            }
            AdAdvertTestConfig testConfig = new AdAdvertTestConfig(
                    null,String.valueOf(flowConfig.getId()),flowDist+"",adAdvertTestConfig.getComputer() == null ? 2:adAdvertTestConfig.getComputer(),type,null,null,1);
            flowTestMapper.insertTestGroup(testConfig);
            testConfigs.add(testConfig);
        }
        if(abFlowGroupParam.getUse_default() != null && abFlowGroupParam.getUse_default() && CollectionUtils.isNotEmpty(testConfigs)){
            //???????????????????????????
            userDefalutCode(positionId,flowConfig.getId(),testConfigs.get(0).getId());
        }
        return flowConfig;
    }

    private void userDefalutCode(Integer positionId, Integer configId, Integer testConfigId) {
        if(testConfigId == null || configId == null){
            return ;
        }
        //??????????????????
        List<AdTestCodeRelation> relas = flowTestMapper.queryDefalutRelation(positionId);
        relas.forEach(rela -> {
            AdTestCodeRelation newRela = new AdTestCodeRelation(null,testConfigId + "", rela.getCode_id(),
                    rela.getNumber(), rela.getMatching(),rela.getOrder_num(), null,null,1);
            flowTestMapper.insertRelaGroup(newRela);
        });

    }

    @Override
    public AbFlowGroupParam findAbflow(Integer positionId, String flowId, String appType, String ip) throws ServiceException {
        //Integer appId = searchAppId(appType);
        //??????positionId???flowId.????????????????????????
        List<AdAdvertFlowConfig> flowConfigs = flowTestMapper.getAdvFlowConfLst(null,null,flowId, null);
        AdAdvertFlowConfig adAdvertFlow = flowConfigs.get(0);
        //????????????????????????????????????????????????????????????AB??????ID??????????????????
        if(StringUtil.isEmpty(adAdvertFlow.getAb_flow_id())){
            throw new ServiceException("??????????????????????????????AB??????ID");
        }
        String expId = adAdvertFlow.getAb_flow_id();
        //???????????????????????????
        ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(expId);
        if(resultMap.getCode() != 200){
            throw new ServiceException(resultMap.getMessage());
        }
        Map<String, Object> abFlowMap = resultMap.getData();
        return createAbFlowGroupParam(abFlowMap, positionId, expId,adAdvertFlow) ;
    }

    @Override
    public void deleteAbflow(String flowId, String appType, String ip) throws ServiceException {
        //???????????????
        //????????????config
        LogOperationConstruct construct = new LogOperationConstruct(tableInfoService, adOperationLogMapper);
        AdAdvertFlowConfig config = flowTestMapper.findAdFlowConfigById(flowId);
        //????????????state
        flowTestMapper.updateStatusById(0,2,flowId);
        construct.logUpdate(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,"state",config.getState()+"","0");
        construct.logUpdate(TableInfoConstant.AD_ADVERT_FLOW_CONFIG,"status",config.getStatus()+"","2");
        List<AdAdvertTestConfig> testConfigs = flowTestMapper.getAdvTestConfLst(flowId,null);
        Set<String> allCodes = Sets.newHashSet();
        if(CollectionUtils.isNotEmpty(testConfigs)){
            testConfigs.forEach(conf->{
                //????????????test???relation
                List<AdTestCodeRelation>  adTestCodeRelationList = flowTestMapper.getTestCodeRelaLst(String.valueOf(conf.getId()),null);
                allCodes.addAll(adTestCodeRelationList.stream().map(adTestCodeRelation ->
                        String.valueOf(adTestCodeRelation.getCode_id())).collect(Collectors.toList()));

                flowTestMapper.updateTestStatusById(0,String.valueOf(conf.getId()));
                flowTestMapper.updateRelationStatusById(0,String.valueOf(conf.getId()));
                construct.logUpdate(TableInfoConstant.AD_ADVERT_TEST_CONFIG,"state",conf.getState()+"","0");
                construct.logUpdateList(TableInfoConstant.AD_TEST_CODE_RELATION,"state",adTestCodeRelationList,"0");
            });
        }
        if(config == null){
            return ;
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
        flowTestMapper.updateCodePutInByDataMap(dataMap);

        construct.saveLog(LogOperationConstruct.PATH_DELETE_FLOW_GROUP,ip,3);
        //?????????ab???????????????  (?????????????????????????????????????????????)
        Integer appId = searchAppId(appType);
        //1?????????
        String token = login();
        HashMap<String, Object> map = Maps.newHashMap();
        map.put("app_id", appId);
        map.put("experiment_id",config.getAbFlowId());
        map.put("state", 2);
        sendEditState(map,token);
        abExpTask.deleteTask(Integer.parseInt(config.getAbFlowId()));
    }


    @Override
    public void sendEditState(String appType,Integer state, String abFlowId, String ip) throws ServiceException {
        Integer appId = searchAppId(appType);
        HashMap<String, Object> map = Maps.newHashMap();
        map.put("app_id", appId);
        map.put("experiment_id",abFlowId);
        map.put("state", state);
        sendEditState(map);
    }
    
    public void sendEditState(Map<String, Object> param) throws ServiceException{
        String token = login();
        sendEditState(param, token);
    }

    private AbFlowGroupParam createAbFlowGroupParam(Map<String, Object> abFlowMap, Integer positionId, String expId,AdAdvertFlowConfig adAdvertFlow) throws ServiceException{
        //??????????????????????????????
        Map<String,Object> expInfo = (Map<String,Object>) abFlowMap.get("exp_info");
        List<Map<String,Object>> testArr = (List<Map<String,Object>>) abFlowMap.get("testArr");
        AbFlowGroupParam param = new AbFlowGroupParam();
        param.setApp_id(MapUtils.getInteger(expInfo,"app_id"));
        param.setName(MapUtils.getString(expInfo,"name"));
        param.setDoc_url(MapUtils.getString(expInfo,"doc_url")); //doc_url????????????
        param.setPosition_id(positionId);
        param.setLayer_id(MapUtils.getInteger(expInfo,"layer_id"));
        param.setExperiment_id(Integer.parseInt(expId));
        param.setCode(adAdvertFlow.getExp_code());
        param.setPub_time(adAdvertFlow.getPub_time());
        fillRatios(param,testArr);
        fillCondition(param,expInfo);
        param.setState_bak(MapUtils.getInteger(expInfo,"state"));
        param.setState(MapUtils.getInteger(expInfo,"state"));
        //todo use_default  pub_time  doc_url
        return param;
    }



    private void fillCondition(AbFlowGroupParam param, Map<String,Object> expInfo) throws ServiceException{
        String condition = MapUtils.getString(expInfo, "condition");
        if(StringUtils.isNotEmpty(condition)){
            List<String> filterParamLst = Arrays.asList(condition.split("&&"));
            for (int i = 0; i < filterParamLst.size(); i++) {
                String filterObj = filterParamLst.get(i);
                JSONObject jsonObject = null;
                try {
                    jsonObject = JSONObject.parseObject(filterObj);
                } catch (Exception e) {
                    throw new ServiceException("AB????????????????????????????????????????????????" + condition);
                }
                String key = (String) jsonObject.get("key");
                String operation = (String) jsonObject.get("operation");
                String value = (String) jsonObject.get("value");
                if("app_version".equals(key)){
                    if(operation.equals("in")){
                        param.setVersion_type(FlowGroupConstant.VERSION_TYPE_ONLY);
                        param.setVersion_ids(value);
                    }
                    if(operation.equals("notin")){
                        param.setVersion_type(FlowGroupConstant.VERSION_TYPE_EX);
                        param.setVersion_ids(value);
                    }
                }
                if("father_channel".equals(key)){
                    if(operation.equals("in")){
                        param.setChannel_type(FlowGroupConstant.CHANNEL_TYPE_ONLY);
                        param.setChannel_ids(value);
                    }
                    if(operation.equals("notin")){
                        param.setChannel_type(FlowGroupConstant.CHANNEL_TYPE_EX);
                        param.setChannel_ids(value);
                    }
                }
                if("is_new".equals(key)){
                    if(operation.equals("in")){
                        //????????????  is_new = 0 ????????????  1:???  2:??? ,ab??????  0??? ???,1 ???
                        if(value == null){
                            param.setIs_new(0);
                        } else if ("0".equals(value)){
                            param.setIs_new(2);
                        } else {
                            param.setIs_new(Integer.parseInt(value));
                        }
                    }
                }
            }
        }
        if(param.getIs_new() == null){
            param.setIs_new(0);
        }
        if(param.getVersion_type() == null){
            param.setVersion_type(1);
        }
        if(param.getChannel_type() == null){
            param.setChannel_type(1);
        }
    }

    private void fillRatios(AbFlowGroupParam param, List<Map<String, Object>> testArr) {
        List<AbTraffic> maps = Lists.newArrayList();
        Integer total = 0;
        for (Map<String, Object> test:testArr) {
            Integer traffic = MapUtils.getInteger(test,"traffic");
            maps.add(new AbTraffic(MapUtils.getInteger(test,"id"),MapUtils.getString(test,"name"),traffic));
            total += traffic;
        }
        maps.add(0,new AbTraffic(-1,"?????????",total));
        param.setRatio(maps);
    }


    public List<AbLayer> getLayerInfo(String appType, Integer expId) throws ServiceException{
        Integer appId = searchAppId(appType);
        //1?????????
        String token = login();
        if(StringUtils.isEmpty(token)){
            throw new ServiceException("????????????,?????????token??????");
        }
        AbResultMap resultMap = abPlatFormService.getCondition(appId,expId,token);
        if(resultMap.getCode() != 0){
            if(resultMap.getCode() == 4001 || resultMap.getCode() == 4005){
                throw new ServiceException(resultMap.getMsg());
            }
            throw new ServiceException("????????????????????????Ab???????????????code"+resultMap.getCode());
        }
        Map<String,Object> result = (Map<String,Object>)resultMap.getData();
        List<Map<String,Object>> abLayers = (List<Map<String,Object>>) result.get("layer_list");
        if(CollectionUtils.isEmpty(abLayers)){
            List<AbLayer> abLayerList = Lists.newArrayList();
            abLayerList.add(new AbLayer(-1,"????????????","100.0"));
            return abLayerList;
        }
        List<AbLayer> newAbLayerList = Lists.newArrayList();
        abLayers.forEach(abLayerMap -> {
            Integer id = MapUtils.getInteger(abLayerMap,"id");
            String name = MapUtils.getString(abLayerMap,"name");
            String traffic = name.substring(name.lastIndexOf("??????") + 2,name.lastIndexOf("%??????"));
            newAbLayerList.add(new AbLayer(id,name,traffic));
        });
        newAbLayerList.add(new AbLayer(-1,"????????????","100.0"));
        return newAbLayerList;
    }

    public ResultMap getVersionInfo(String appType)  {
        List<String> appVersions = toolMofangService.findVersionInfo(appType);
        if(CollectionUtils.isEmpty(appVersions)){
            return ResultMap.success();
        }
        List<String> appVersionList = appVersions.stream().filter(appVersion -> VersionConstant.APP_XLD.contains(appType) ?
                VersionUtil.compare(appVersion, VersionConstant.XLD_DEFALUT_VERSION) : VersionUtil.compare(appVersion, VersionConstant.XY_DEFALUT_VERSION)).collect(Collectors.toList());
        return ResultMap.success(appVersionList);
    }

    private void sendEditState(Map<String, Object> param, String token) throws ServiceException{
        AbResultMap resultMap = abPlatFormService.sendEditState(param,token);
        if(resultMap.getCode() != 0){
            if(resultMap.getCode() == 4001 || resultMap.getCode() == 4005){
                throw new ServiceException(resultMap.getMsg());
            }
            throw new ServiceException("????????????????????????Ab???????????????code"+resultMap.getCode());
        }
        flowTestMapper.updateStatus(Integer.parseInt(param.get("state").toString()),param.get("experiment_id").toString());
    }

    //??????????????????
    private List<Integer> saveFlowDistribution(AbFlowDistribution abFlowDistribution, String token) throws ServiceException {
        Map<Integer,Integer> groups = Maps.newHashMap();
        List<Integer> groupIds = Lists.newArrayList();
        if(MapUtils.isEmpty(abFlowDistribution.getGroup_maps())){
            List<Map<String, Object>> groupInfos = getGroupInfo(abFlowDistribution, token);
            if(CollectionUtils.isEmpty(groupInfos)){
                throw new ServiceException("?????????????????????,???????????????????????????");
            }
            List<AbTraffic> ratio = abFlowDistribution.getRatio();
            for (int i = 0 ; i < groupInfos.size() ; i ++) {
                Map<String, Object> groupInfo = groupInfos.get(i);
                Integer id = MapUtils.getInteger(groupInfo,"id");
                AbTraffic ratioInfo = ratio.get(i+1);
                if(ratioInfo.getId() == null || ratioInfo.getId() == -1){
                    groups.put(id,ratioInfo.getTraffic());
                } else {
                    groups.put(id,ratioInfo.getTraffic());
                }
                groupIds.add(id);
            }
        }
        abFlowDistribution.setGroup_maps(groups);
        AbResultMap resultMap = abPlatFormService.saveAbFlowDistribution(abFlowDistribution,token);
        if (resultMap.getCode() != 0) {
            if(resultMap.getCode() == 4001 || resultMap.getCode() == 4005){
                throw new ServiceException(resultMap.getMsg());
            }
            throw new ServiceException("?????????????????????,AB???????????????????????????????????????"+resultMap.getMsg());
        }
        return groupIds;
    }

    private Integer subTraffic(Integer traffic, double oldtraffic) {
        if(oldtraffic == 0.0 ||  oldtraffic == 0 || traffic == 0){
            return traffic;
        }
        if(oldtraffic == 100 || oldtraffic == 100.0){
            return 0;
        }
        BigDecimal divi = new BigDecimal(10);
        //????????????
        BigDecimal tra = new BigDecimal(traffic);
        BigDecimal oldTra = new BigDecimal(oldtraffic);
        BigDecimal total = new BigDecimal(100);
        BigDecimal sub = total.subtract(oldTra); //???????????????
        return tra.multiply(sub.divide(total)).setScale(1, RoundingMode.HALF_UP).intValue();
    }

    private List<Map<String,Object>> getGroupInfo(AbFlowDistribution abFlowDistribution, String token) throws ServiceException {
        AbResultMap groupInfo = abPlatFormService.searchGroupByExpId(abFlowDistribution.getExp_id(),token);
        if(groupInfo.getCode() != 0){
            if(groupInfo.getCode() == 4001 || groupInfo.getCode() == 4005){
                throw new ServiceException(groupInfo.getMsg());
            }
            throw new ServiceException("?????????????????????,AB???????????????????????????????????????");
        }
        return (List<Map<String,Object>>)groupInfo.getData();
    }


    /**
     * ??????????????????
     * @param abExperiment
     * @param token
     * @return
     * @throws ServiceException
     */
    private Integer saveExperiment(AbExperiment abExperiment, String token) throws ServiceException{
        AbResultMap resultMap = abPlatFormService.saveAbExperiment(abExperiment,token);
        if (resultMap.getCode() != 0) {
            if(resultMap.getCode() != 0){
                if(resultMap.getCode() == 4001){
                    throw new ServiceException(resultMap.getMsg());
                }
                if(resultMap.getCode() == 1004){
                    if(resultMap.getMsg().indexOf("????????????") >= 0){
                        abExperiment.incrCode();
                        return saveExperiment(abExperiment, token);
                    }
                    throw new ServiceException(resultMap.getMsg());
                }
                if(resultMap.getCode() == 1024){
                    throw new ServiceException("?????????????????????AB????????????????????????????????????AB?????????????????????????????????????????????");
                }
                if(resultMap.getCode() == 1005){
                    throw new ServiceException(resultMap.getMsg());
                }
                if(resultMap.getCode() == 1008){
                    throw new ServiceException(resultMap.getMsg());
                }
                throw new ServiceException("???????????????,AB???????????????????????????????????????"+resultMap.getMsg());
            }
        }
        if(abExperiment.getId() == null){
            Map<String,Integer> result = (Map<String,Integer>)resultMap.getData();
            Integer id = MapUtils.getInteger(result,"exp_id");
            return id;
        } else {
            return abExperiment.getId();
        }
    }

    /**
     * ??????????????? ???????????????id???
     * @param abLayer
     * @param token
     * @return
     * @throws ServiceException
     */
    private Integer saveLayer(AbLayer abLayer, String token) throws ServiceException {
        //?????????????????????????????????
        log.error(abLayer.getName());
        if(CollectionUtils.isEmpty(abLayer.getTags())){
            List<Integer> tags = getAdTag(abLayer.getApp_id(),token);
            if(CollectionUtils.isEmpty(tags)){
                throw new ServiceException("????????????????????????AB????????????????????????");
            }
            abLayer.setTags(tags);
        }
        AbResultMap resultMap = abPlatFormService.saveLayer(abLayer,token);
        if (resultMap.getCode() != 0) {
            if(resultMap.getCode() != 0){
                if(resultMap.getCode() == 4001){
                    throw new ServiceException(resultMap.getMsg());
                }
                throw new ServiceException("???????????????,AB???????????????????????????????????????"+ resultMap.getMsg());
            }
        }
        Map<String,Integer> result = (Map<String,Integer>)resultMap.getData();
        Integer id = MapUtils.getInteger(result,"id");
        return id;
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
     * ????????????????????????????????????????????????
     * @param defaultPosCodeLstVos
     * @param channelIds
     * @param version1
     * @param version2
     * @return
     */
    private List<AdvCodeInfoVo> getAdvCodesByFilter(List<AdvCodeInfoVo> defaultPosCodeLstVos,
                                                    String channelIds,String channel2Ids, String version1,String version2,String version3,String version4) {
        List<AdvCodeInfoVo> newAdvCodes = Lists.newArrayList();
        for (int j = 0; j < defaultPosCodeLstVos.size(); j++) {
            AdvCodeInfoVo vo = defaultPosCodeLstVos.get(j);

            boolean chaneFlag = false;


            //??????AB????????? ?????? ?????????????????????
            if(StringUtil.isEmpty(channelIds) || StringUtil.isEmpty(channel2Ids) || FlowGroupConstant.CHANNEL_TYPE_ALL == vo.getChannel_type()){
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
                        VersionUtil.compare(vo.getVersion2(),version1)){
                    if(StringUtils.isEmpty(version2) || VersionUtil.compare(version2, vo.getVersion1())){
                        versionFlag1 = true;
                    }
                }
                if(StringUtils.isNotEmpty(version2) &&
                        VersionUtil.compare(version2,vo.getVersion1())){
                    if(StringUtils.isEmpty(version1) || VersionUtil.compare(vo.getVersion2(), version1)){
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
                if(StringUtils.isNotEmpty(version4)){
                    versionFlag4 = true;
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
     * ??????????????????????????????
     * @param flowConfig
     * @param abFlowMap
     * @param defaultPosCodeLstVos
     * @return
     */
    private ABFlowGroupVo returnFlowTest(AdAdvertFlowConfig flowConfig, List<AdvCodeInfoVo> defaultPosCodeLstVos, Map<String, Object> abFlowMap){

        String channelIds = "",channel2Ids = "",version1 = "",version2 = "",version3 = "",version4 = "";
        List<Map<String,Object>> testArr = Lists.newArrayList();
        if(MapUtils.isNotEmpty(abFlowMap)){
            channelIds = MapUtils.getString(abFlowMap,"channel");
            channel2Ids = MapUtils.getString(abFlowMap,"channel2");
            version1 = MapUtils.getString(abFlowMap,"version1");
            version2 = MapUtils.getString(abFlowMap,"version2");
            version3 = MapUtils.getString(abFlowMap,"version3");
            version4 = MapUtils.getString(abFlowMap,"version4");
            testArr = (List<Map<String, Object>>) abFlowMap.get("testArr");
        }
        String channelNames = getChannelNames(channelIds);
        //????????????
        int flowType = 1;
        List<AdvCodeInfoVo> newAdvCodes = Lists.newArrayList();
        if(FlowGroupConstant.DEFAULT_GROUP_TYPE == flowConfig.getType()){
            newAdvCodes.addAll(defaultPosCodeLstVos);
        }else if(FlowGroupConstant.HANDLER_GROUP_TYPE == flowConfig.getType()) {//????????????????????????????????????????????????
            newAdvCodes = getAdvCodesByFilter(defaultPosCodeLstVos, channelIds,channel2Ids, version1, version2,version3,version4);
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
            appVersion = "????????????"+version1;
        }else if(StringUtil.isEmpty(version1) && StringUtil.isNotEmpty(version2)){
            appVersion = "????????????"+version2;
        }else if (StringUtil.isNotEmpty(version3)){
            appVersion = "????????????:"+version3;
        }else if (StringUtil.isNotEmpty(version4)){
            appVersion = "????????????:"+version4;
        }


        ABFlowGroupVo abFlowGroupVo = new ABFlowGroupVo(flowConfig.getId(),flowConfig.getName(),
                channelNames,flowType, flowConfig.getTest_state(),appVersion, flowConfig.getAb_flow_id(),newAdvCodes,newTestConfs,flowConfig.getPub_time(),flowConfig.getStatus(),null,null);

        return abFlowGroupVo;
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
                    conf.getId(), conf.getAb_test_id(), null, conf.getComputer(), conf.getType(), posCodeLstVos,null);
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
}
