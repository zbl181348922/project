package com.miguan.xuanyuan.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.miguan.xuanyuan.common.constant.RedisKeyConstant;
import com.miguan.xuanyuan.common.constant.StrategyGroupConstant;
import com.miguan.xuanyuan.common.constant.XyConstant;
import com.miguan.xuanyuan.common.exception.ServiceException;
import com.miguan.xuanyuan.common.exception.ValidateException;
import com.miguan.xuanyuan.common.util.*;
import com.miguan.xuanyuan.dto.*;
import com.miguan.xuanyuan.dto.ab.*;
import com.miguan.xuanyuan.dto.request.*;
import com.miguan.xuanyuan.entity.*;
import com.miguan.xuanyuan.mapper.XyStrategyGroupMapper;
import com.miguan.xuanyuan.service.*;
import com.miguan.xuanyuan.service.common.RedisService;
import com.miguan.xuanyuan.task.AbExpTask;
import com.miguan.xuanyuan.vo.AdCodeVo;
import com.miguan.xuanyuan.vo.StrategyCodeVo;
import com.miguan.xuanyuan.vo.StrategyGroupVo;
import com.miguan.xuanyuan.vo.StrategyVo;
import com.mysql.cj.xdevapi.JsonArray;
import io.swagger.models.auth.In;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import springfox.documentation.spring.web.json.Json;
import tool.util.NumberUtil;

import javax.annotation.Resource;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StrategyGroupServiceImpl implements StrategyGroupService {

    @Resource
    public XyStrategyGroupMapper strategyGroupMapper;

    @Resource
    XyStrategyService xyStrategyService;

    @Resource
    XyStrategyCodeService xyStrategyCodeService;

    @Resource
    private RedisService redisService;

    @Resource
    private MofangService mofangService;

    @Resource
    AbPlatFormService abPlatFormService;

    @Resource
    XyAdPositionService xyAdPositionService;

    @Resource
    AbExpService abExpService;

    @Resource
    XyAdCodeService xyAdCodeService;

    @Resource
    AdCodeService adCodeService;

    @Resource
    private AbExpTask abExpTask;

    @Resource
    PlanService planService;

    @Resource
    OriginalityService originalityService;



    public List<StrategyGroupDto> getGroupList(long adPosId, int offset, int limit) {
        return strategyGroupMapper.getGroupList(adPosId, offset, limit);
    }

    /**
     * ??????????????????
     *
     * @param positionId
     * @return
     * @throws ServiceException
     */
    public Map<String, Object> getGroupDetail(Long positionId) throws ServiceException {
        AdPositionDetailDto adPositionDetailDto = xyAdPositionService.getPositionDetail(positionId);
        if (adPositionDetailDto == null) {
            throw new ServiceException("??????????????????");
        }

        int offset = 0;
        int pageSize = XyConstant.INIT_MAX_PAGE_SIZE;

        List<StrategyGroupDto> list = this.getGroupList(positionId, offset, pageSize);
        if (CollectionUtils.isNotEmpty(list)) {
            list.forEach(strategyGroupDto -> {
                int status = strategyGroupDto.getStatus();
                if (status == 1 && strategyGroupDto.getBeginTime() != null) {
                    long curTs = DateUtils.getCurrentTimestamp();
                    long beginTs = 0;
                    try {
                        beginTs = DateUtils.getDateStrTimestamp(strategyGroupDto.getBeginTime());
                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                    if (beginTs > curTs) {
                        status = 2; // ?????????
                    }
                }
                strategyGroupDto.setStatus(status);
            });
        }

        Map<String, Object> data = new HashMap<>();

        data.put("positionId", positionId);
        data.put("packageName", adPositionDetailDto.getPackageName());
        data.put("appId", adPositionDetailDto.getAppId());
        data.put("list", list);

        return data;
    }


    public long insert(XyStrategyGroup xyStrategyGroup){
        long id = strategyGroupMapper.insert(xyStrategyGroup);
        return id;
    }

    public int update(XyStrategyGroup xyStrategyGroup) {
        return strategyGroupMapper.update(xyStrategyGroup);
    }

    /**
     * ???????????????
     *
     * @param positionId
     */
    public void initStrategyGroup(long positionId) {
        XyStrategyGroup xyStrategyGroup = this.initStrategyGroupEntity(positionId);
        this.insert(xyStrategyGroup);
        XyStrategy xyStrategy = xyStrategyService.initStrategyEntity(xyStrategyGroup.getId());
        xyStrategyService.insert(xyStrategy);
    }

    public XyStrategyGroup initStrategyGroupEntity(long positionId) {
        String curTime = DateUtils.getCurrentDateTimeStr();
        XyStrategyGroup xyStrategyGroup = new XyStrategyGroup();
        xyStrategyGroup.setGroupName(XyConstant.STRATEGY_GROUP_DEFAULT_NAME);
        xyStrategyGroup.setPositionId(positionId);
        xyStrategyGroup.setAbId(0L);
        xyStrategyGroup.setAbExpCode("");
        xyStrategyGroup.setBeginTime(curTime);
        xyStrategyGroup.setStatus(1);
        xyStrategyGroup.setIsDel(0);
        return xyStrategyGroup;
    }

    /**
     * ??????????????????
     *
     * @param strategyGroupId
     * @return
     */
    public XyStrategyGroup getDataById(long strategyGroupId) {
        return strategyGroupMapper.getDataById(strategyGroupId);
    }

    /**
     * ????????????id????????????
     *
     * @param abId
     * @param status
     * @return
     */
    public  int updateStrategyGroupByAbId(Long abId, Integer status) {
        return strategyGroupMapper.updateStrategyGroupByAbId(abId, status);
    }

    /**
     * ??????ab???????????????
     *
     * @param position
     * @param abId
     * @return
     */
    public static String getAdExpCode(Long position, Long abId) {
        return StrategyGroupConstant.AB_EXP_CODE_PREFIX + "_" + position + "_" + abId;
    }


    /**
     * ???????????????????????????
     *
     * @param strategyGroupId
     * @return
     * @throws ParseException
     */
    public StrategyGroupVo getStrategyDetail(long strategyGroupId) throws ParseException, ServiceException {
        XyStrategyGroup strategyGroup = strategyGroupMapper.getDataById(strategyGroupId);
        if (strategyGroup == null) {
            return null;
        }

        int abStatus = strategyGroup.getStatus();
        if (abStatus == 1 && strategyGroup.getBeginTime() != null) {
            long curTs = DateUtils.getCurrentTimestamp();
            long beginTs = DateUtils.getDateStrTimestamp(strategyGroup.getBeginTime());
            if (beginTs > curTs) {
                abStatus = 2; // ?????????
            }
        }

        long positionId = strategyGroup.getPositionId(); //?????????id
        AdPositionDetailDto adPositionDetailDto = xyAdPositionService.getPositionDetail(positionId);

        String channel = "";
        String appVersion = "";

        Long abId = strategyGroup.getAbId();
        Map<String, Object> abFlowMap = Maps.newHashMap();
        if (abId != null && abId > 0l) {
            //???????????????????????????
            ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(String.valueOf(abId));
            if(resultMap.getCode() != 0){
                throw new ServiceException(resultMap.getMessage());
            }
            abFlowMap = resultMap.getData();
            Map<String, String> filterData = abExpService.getFilterMap(abFlowMap);
            channel = (String) filterData.get("channel");
            appVersion = (String)filterData.get("appVersion");

        }

        StrategyGroupVo strategyGroupVo = new StrategyGroupVo();
        strategyGroupVo.setStrategyGroupId(strategyGroupId);
        strategyGroupVo.setPositionId(positionId);
        strategyGroupVo.setChannel(channel);
        strategyGroupVo.setAppVersion(appVersion);
        strategyGroupVo.setAbId(strategyGroup.getAbId());
        strategyGroupVo.setBeginTime(strategyGroup.getBeginTime());
        strategyGroupVo.setAbStatus(abStatus);
        strategyGroupVo.setPackageName(adPositionDetailDto.getPackageName());
        strategyGroupVo.setAdType(adPositionDetailDto.getAdType());
        strategyGroupVo.setAppId(adPositionDetailDto.getAppId());
        strategyGroupVo.setCustomSwitch(strategyGroup.getCustomSwitch());

        List<StrategyVo> strategylist = new ArrayList<>();
        strategyGroupVo.setStrategylist(strategylist); //??????????????????

        List<StrategyCodeVo> allstrategyCodelist = new ArrayList<>();
        strategyGroupVo.setAllCodeList(allstrategyCodelist); //???????????????????????????


        List<XyStrategyCodeDto> allStrategyCodeDtoLsit = xyStrategyCodeService.getAllStrategyCodeList(strategyGroupId, positionId);
        if (CollectionUtils.isNotEmpty(allStrategyCodeDtoLsit)) {
            allStrategyCodeDtoLsit.forEach(strategyCodeDto->{
                StrategyCodeVo strategyCodeVo = new StrategyCodeVo();
                BeanUtils.copyProperties(strategyCodeDto, strategyCodeVo);
                allstrategyCodelist.add(strategyCodeVo);
            });
        }

        //????????????
        List<XyStrategyDto> strategyDtoList = xyStrategyService.getStrategyList(strategyGroupId);
        if (CollectionUtils.isNotEmpty(strategyDtoList)) {
            strategyDtoList.forEach(strategyDto->{
                StrategyVo strategyVo = new StrategyVo();
                BeanUtils.copyProperties(strategyDto, strategyVo);
                strategyVo.setStrategyId(strategyDto.getId());
                strategyVo.setCustomField(strategyVo.convertCustomField(strategyDto.getCustomField()));
                strategylist.add(strategyVo); //????????????
                List<StrategyCodeVo> strategyCodelist = new ArrayList<>();
                strategyVo.setAdCodeList(strategyCodelist); //???????????????????????????

                Integer sortType = strategyVo.getSortType();

                //??????????????????????????????
                List<XyStrategyCodeDto> strategyCodeDtoList = xyStrategyCodeService.getStrategyCodeList(strategyDto.getId());

                StrategyCodeVo planCodeVo = new StrategyCodeVo(); //?????????????????????

                if (CollectionUtils.isNotEmpty(strategyCodeDtoList)) {
                    final List<StrategyCodeVo> finalStrategyCodelist = new ArrayList<>();
                    strategyCodeDtoList.forEach(strategyCodeDto->{
                        StrategyCodeVo strategyCodeVo = new StrategyCodeVo();
                        BeanUtils.copyProperties(strategyCodeDto, strategyCodeVo);

                        String sourceCodeId = strategyCodeVo.getSourceCodeId();
                        if (sourceCodeId.startsWith("xy")) {
                            BeanUtils.copyProperties(strategyCodeVo, planCodeVo);
                        } else {
                            finalStrategyCodelist.add(strategyCodeVo);
                        }
                    });

                    List<StrategyCodeVo> finalStrategyCodelist1 = new ArrayList<>();
                    finalStrategyCodelist1 = finalStrategyCodelist;
                    if (sortType == StrategyGroupConstant.SORT_TYPE_AUTO) {
                        finalStrategyCodelist1 = this.sortAutoMulti(finalStrategyCodelist1, adPositionDetailDto.getPackageName());
                    }

                    if (planCodeVo.getCodeId() != null) {
                        //????????????????????????????????????????????????
                        int status = 0;
                        Plan planData = planService.getPlanByPositionId(positionId);
                        if (planData != null) {
                            boolean planActive = originalityService.isPlanActive(planData.getPutTimeType(), planData.getStartDate(), planData.getEndDate()
                                                            ,planData.getTimeSetting(), planData.getTimesConfig());
                            status = planActive ? 1 : 0;
                            planCodeVo.setStatus(status);
                        }
                        strategyCodelist.add(planCodeVo);
                    }
                    strategyCodelist.addAll(finalStrategyCodelist1);
                    strategyVo.setAdCodeList(strategyCodelist);

                }
            });
        }
        return strategyGroupVo;
    }


    public List<StrategyCodeVo> sortAutoMulti(List<StrategyCodeVo> strategyCodelist, String packageName) {
        Map<String, Object> sortParam = new HashMap<>();
        sortParam.put("isNew", "-1");  //??????????????????1???????????????0????????????
        sortParam.put("city", "-1");  //??????
        sortParam.put("channel", "-1"); //??????id
        sortParam.put("appPackage", packageName); //??????

        List<AdCodeDto> advertCodes = new ArrayList<>();
        Map<Long, StrategyCodeVo> strategCodeyMap = new HashMap<>();
        for (StrategyCodeVo strategyCodeVo : strategyCodelist) {
            AdCodeDto adCodeDto = new AdCodeDto();
//            BeanUtils.copyProperties(strategyCodeVo, adCodeDto);
            try {
                org.apache.commons.beanutils.BeanUtils.copyProperties(adCodeDto, strategyCodeVo);
            } catch (Exception e) {

            }
            advertCodes.add(adCodeDto);
            strategCodeyMap.put(strategyCodeVo.getCodeId(), strategyCodeVo);
        }

        adCodeService.sortAutoMulti(advertCodes, sortParam);
//        adCodeService.setAdvertCodeDelayMillis(advertCodes);

        List<StrategyCodeVo> newList = new ArrayList<>();
        for (AdCodeDto adCodeDto : advertCodes) {
            newList.add(strategCodeyMap.get(adCodeDto.getCodeId()));
        }
        return newList;
    }


    /**
     * ??????ab??????
     *
     * @param abTestStatusRequest
     */
    public void changeAbStatus(AbTestStatusRequest abTestStatusRequest) throws ServiceException, ParseException {

        Long strategyGroupId = abTestStatusRequest.getStrategyGroupId();
        Integer status = abTestStatusRequest.getStatus();

        if (strategyGroupId == null) {
            throw new ServiceException("strategyGroupId????????????");
        }
        if (abTestStatusRequest.getStatus() == null) {
            throw new ServiceException("status????????????");
        }

        if ((status == StrategyGroupConstant.AB_STATUS_DELAY_RUN)
                && abTestStatusRequest.getBeginTime() == null) {
            throw new ServiceException("beginTime????????????");
        }

        long beginTime = 0;
        if (status == StrategyGroupConstant.AB_STATUS_DELAY_RUN) {
            try {
                beginTime = DateUtils.getDateStrTimestamp(abTestStatusRequest.getBeginTime());
            } catch (ParseException e) {
                throw new ServiceException("beginTime??????????????????");
            }
        }

        XyStrategyGroup xyStrategyGroup = getDataById(strategyGroupId);
        if (xyStrategyGroup == null) {
            throw new ServiceException("?????????????????????");
        }
        if (xyStrategyGroup.getAbId() <= 0) {
            throw new ServiceException("??????????????????????????????");
        }
        long curTs = DateUtils.getCurrentTimestamp();

        long beginTs = 0;
        if (xyStrategyGroup.getBeginTime() != null) {
            beginTs = DateUtils.getDateStrTimestamp(xyStrategyGroup.getBeginTime());
        }
        int abStatus = xyStrategyGroup.getStatus(); //????????????
        if (abStatus == 1) {
            if (beginTs > curTs) {
                abStatus = 2; // ?????????
            }
        }

        StrategyGroupVo strategyGroupVo = getStrategyDetail(strategyGroupId);

        String packageName = strategyGroupVo.getPackageName();
        Integer abId = strategyGroupVo.getAbId().intValue();
        Integer abState = 1;

        switch (status) {
            case StrategyGroupConstant.AB_STATUS_RUN:
                if (abStatus == 1) {
                    throw new ServiceException("??????????????????????????????????????????");
                }
                //????????????????????????
//                if (abStatus == 2) {
//                    String beginTimeStr = DateUtils.getDataStrFromTimestamp(curTs);
//                    xyStrategyGroup.setBeginTime(beginTimeStr);
//                }
                String beginTimeStr = DateUtils.getDataStrFromTimestamp(curTs);
                xyStrategyGroup.setBeginTime(beginTimeStr);
                xyStrategyGroup.setStatus(StrategyGroupConstant.AB_STATUS_RUN);

                abExpService.sendEditState(packageName, abId, abState);
                break;
            case StrategyGroupConstant.AB_STATUS_STOP:
                xyStrategyGroup.setIsDel(XyConstant.DEL_STATUS);
                abState = 2;
                abExpService.sendEditState(packageName, abId, abState);
                abExpTask.deleteTask(abId);
                break;
            case StrategyGroupConstant.AB_STATUS_DELAY_RUN:
                if (abStatus == 1) {
                    throw new ServiceException("????????????????????????????????????????????????");
                }
                if (beginTime < curTs) {
                    throw new ServiceException("beginTime??????????????????????????????");
                }
                xyStrategyGroup.setStatus(StrategyGroupConstant.AB_STATUS_RUN);
                xyStrategyGroup.setBeginTime(abTestStatusRequest.getBeginTime());
                abExpService.sendDelayTaskState(packageName, abId, abState, abTestStatusRequest.getBeginTime());
                break;
            default:
                throw new ServiceException("status?????????");
        }
        strategyGroupMapper.update(xyStrategyGroup);
    }


    @Transactional
    public void saveStragetyCode(StrategyGroupVo strategyGroupVo, AbStrategyCodeRequest abStrategyCodeRequest) throws ParseException,ServiceException  {
        Long strategyGroupId = abStrategyCodeRequest.getStrategyGroupId();

        Long positionId = strategyGroupVo.getPositionId();

        Map<Long, StrategyVo> strategyMap = new HashMap<>(); //??????id????????????
        List<StrategyVo> strategylist = strategyGroupVo.getStrategylist();
        if (CollectionUtils.isNotEmpty(strategylist)) {
            strategyMap = strategylist.stream().collect(Collectors.toMap(StrategyVo::getStrategyId, vo -> vo));
        }

        if (MapUtils.isEmpty(strategyMap)) {
            return;
        }

        if (abStrategyCodeRequest.getCustomSwitch() == null) {
            throw new ServiceException("customSwitch????????????");
        }

        XyStrategyGroup newStrategyGroup = new XyStrategyGroup();
        newStrategyGroup.setId(strategyGroupId);
        newStrategyGroup.setCustomSwitch(abStrategyCodeRequest.getCustomSwitch());
        newStrategyGroup.setStatus(null); //?????????
        newStrategyGroup.setIsDel(null); //?????????
        update(newStrategyGroup);

        Map<Long, StrategyCodeVo> allCodeMap = new HashMap<>(); //?????????????????????
        List<StrategyCodeVo> allCodeList = strategyGroupVo.getAllCodeList();
        if (CollectionUtils.isNotEmpty(allCodeList)) {
            allCodeMap = allCodeList.stream().collect(Collectors.toMap(StrategyCodeVo::getCodeId, vo -> vo));
        }

        List<AbStrategyRequest> list = abStrategyCodeRequest.getList();
        if (CollectionUtils.isNotEmpty(list)) {
            for(AbStrategyRequest abStrategyRequest : list) {
                Long strategyId = abStrategyRequest.getStrategyId();
                if (!strategyMap.containsKey(strategyId)) {
                    throw new ServiceException("strategyId:" + strategyId + "??????????????????/?????????");
                }

                String customField = XyStrategyService.getInitCustomField();
                List<String> customFieldList = abStrategyRequest.getCustomField();
                if (CollectionUtils.isNotEmpty(customFieldList) && customField.length() == 6) {
                    customField = JSON.toJSONString(customFieldList);
                }
                XyStrategy xyStrategy = new XyStrategy();
                xyStrategy.setId(abStrategyRequest.getStrategyId());
                xyStrategy.setCustomField(customField);
                xyStrategy.setSortType(abStrategyRequest.getSortType());
                xyStrategyService.update(xyStrategy); //??????????????????

                xyStrategyCodeService.updateStrategyCodeClose(strategyId); //?????????????????????
                //?????????????????????
                List<AbStrategyCodeFlowRequest> adCodeList = abStrategyRequest.getAdCodeList();
                if (CollectionUtils.isNotEmpty(adCodeList)) {
                    long orderNum = 1l;
                    for(AbStrategyCodeFlowRequest abStrategyCodeFlowRequest : adCodeList) {
                        Long codeId = abStrategyCodeFlowRequest.getCodeId();

                        if (!allCodeMap.containsKey(codeId)) {
                            throw new ServiceException("adCodeId:" + codeId + "?????????");
                        }
                        StrategyCodeVo strategyCodeVo = allCodeMap.get(codeId);

                        XyStrategyCode xyStrategyCode = new XyStrategyCode();
                        xyStrategyCode.setStrategyId(strategyId);
                        xyStrategyCode.setAdCodeId(codeId);
                        xyStrategyCode.setCodeId(strategyCodeVo.getSourceCodeId());
                        xyStrategyCode.setPriority(abStrategyCodeFlowRequest.getPriority());
                        xyStrategyCode.setRateNum(abStrategyCodeFlowRequest.getRateNum());
                        xyStrategyCode.setOrderNum(orderNum);
                        xyStrategyCode.setStatus(1);
                        xyStrategyCodeService.putStrategyCode(xyStrategyCode);
                        orderNum++;

                        AdCodeVo adCodeVo = xyAdCodeService.findById(codeId);
                        AdCodeRequest adCodeRequest = new AdCodeRequest();
                        adCodeRequest.setId(adCodeVo.getId());
                        adCodeRequest.setShowIntervalSec(adCodeVo.getShowIntervalSec());
                        adCodeRequest.setShowLimitDay(adCodeVo.getShowLimitDay());
                        adCodeRequest.setShowLimitHour(adCodeVo.getShowLimitHour());
                        adCodeRequest.setVersionOperation(adCodeVo.getVersionOperation());
                        adCodeRequest.setVersions(adCodeVo.getVersions());
                        adCodeRequest.setChannelOperation(adCodeVo.getChannelOperation());
                        adCodeRequest.setChannels(adCodeVo.getChannels());
                        adCodeRequest.setNote(adCodeVo.getNote());
                        adCodeRequest.setStatus(1);
                        xyAdCodeService.update(adCodeRequest);
                    }

                    //???????????????????????????
                    xyAdCodeService.updateCodeNotPutIn(positionId);
                }

            }

        }
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param positionId
     * @param groupName
     * @return
     */
    public XyStrategyGroup getDataByPositionAndGroupName(Long strategyGroupId, Long positionId, String groupName) {
        return strategyGroupMapper.getDataByPositionAndGroupName(strategyGroupId, positionId, groupName);
    }


    public void addAbExpCheck(AbTestRequest abTestRequest) throws ServiceException {
        Long positionId = abTestRequest.getPositionId();
        String expName = abTestRequest.getExpName();

        Long strategyGroupId = 0L;
        List<AbItem> flowRate = abTestRequest.getFlowRate();

        if (positionId == null) {
            throw new ServiceException("positionId??????");
        }

        if (StringUtils.isEmpty(expName)) {
            throw new ServiceException("expName??????");
        }

        AdPositionDetailDto adPositionDetailDto = xyAdPositionService.getPositionDetail(positionId);
        if (adPositionDetailDto == null) {
            throw new ServiceException("positionId??????????????????");
        }
        abTestRequest.setPackageName(adPositionDetailDto.getPackageName());

        if (CollectionUtils.isEmpty(flowRate)) {
            throw new ServiceException("flowRate????????????");
        }

        XyStrategyGroup xyStrategyGroup = getDataByPositionAndGroupName(strategyGroupId, positionId, expName);
        if (xyStrategyGroup != null) {
            throw new ServiceException("????????????????????????");
        }

        String versionIds = abTestRequest.getVersionIds();
        String versionOperation = abTestRequest.getVersionOperation();
        if (!versionOperation.equals(XyConstant.OPERA_IN) && !versionOperation.equals(XyConstant.OPERA_NOT_IN) && versionIds !=null && versionIds.contains(",")) {
            throw new ServiceException("versionIds????????????");
        }


        int total = 0;
        int contrast = 0;
        int test = 0;
        //??????????????????
        List<String> AbItemTypeList = Arrays.asList(new String[]{StrategyGroupConstant.STRATEGY_TYPE_TOTAL, StrategyGroupConstant.STRATEGY_TYPE_CONTRAST, StrategyGroupConstant.STRATEGY_TYPE_TEST});
        for (AbItem abItem : flowRate) {
            if (abItem.getType() == null || !AbItemTypeList.contains(abItem.getType())) {
                throw new ServiceException("flowRate type??????");
            }

            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TOTAL)) {
                total = abItem.getVal();
            }

            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_CONTRAST)) {
                contrast = abItem.getVal();
            }

            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TEST)) {
                test += abItem.getVal();
            }

        }

        if ((contrast + test) > total) {
            throw new ServiceException("???????????????????????????????????????????????????");
        }
    }


    /**
     * ??????ab??????
     *
     * @param abTestRequest
     */
    @Transactional
    public void addAbExp(AbTestRequest abTestRequest, Long abId, String abExpCode, List<AbItem> abItemList) throws ServiceException {

        Long positionId = abTestRequest.getPositionId();
        String expName = abTestRequest.getExpName();
        Long strategyGroupId = 0L;
        List<AbItem> flowRate = abTestRequest.getFlowRate();

//        String abExpCode = getAdExpCode(positionId, abId);
        String beginTime = null;
        Integer customSwitch = 0;

        XyStrategyGroup newStrategyGroup = new XyStrategyGroup();
        newStrategyGroup.setPositionId(positionId);
        newStrategyGroup.setGroupName(expName);
        newStrategyGroup.setAbId(abId);
        newStrategyGroup.setAbExpCode(abExpCode);
        newStrategyGroup.setCustomSwitch(customSwitch);
        newStrategyGroup.setStatus(StrategyGroupConstant.AB_STATUS_UNRUN);

        //??????????????????
        insert(newStrategyGroup);

        flowRate = abItemList;

        for (AbItem abItem : flowRate) {
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TOTAL)) {
                continue;
            }

//            Long abItemId = (long)(DateUtils.getCurrentTimestamp()%100000000);
            Long abItemId = abItem.getAbItemId();
            Integer abRate = abItem.getVal();
            String customField = XyStrategyService.getInitCustomField();

            XyStrategy xyStrategy = new XyStrategy();
            xyStrategy.setAbItemId(abItemId);
            xyStrategy.setStrategyGroupId(newStrategyGroup.getId());
            xyStrategy.setAbRate(abRate);
            xyStrategy.setSortType(StrategyGroupConstant.SORT_TYPE_MANUAL);
            xyStrategy.setStatus(1);
            xyStrategy.setCustomField(customField);
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_CONTRAST)) {
                xyStrategy.setType(StrategyGroupConstant.STRATEGY_TYPE_CONTRAST_VAL);
            }
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TEST)) {
                xyStrategy.setType(StrategyGroupConstant.STRATEGY_TYPE_TEST_VAL);
            }
            xyStrategyService.insert(xyStrategy);
        }
    }

    public void editAbExpCheck(AbTestRequest abTestRequest) throws ServiceException {
        Long strategyGroupId = abTestRequest.getStrategyGroupId();
        Long positionId = abTestRequest.getPositionId();
        String expName = abTestRequest.getExpName();
        List<AbItem> flowRate = abTestRequest.getFlowRate();

        if (strategyGroupId == null) {
            throw new ServiceException("strategyGroupId????????????");
        }

        if (positionId == null) {
            throw new ServiceException("positionId????????????");
        }

        if (StringUtils.isEmpty(expName)) {
            throw new ServiceException("expName????????????");
        }

        XyStrategyGroup strategyGroupInfo = getDataById(strategyGroupId);
        if (strategyGroupInfo == null) {
            throw new ServiceException("strategyGroupId?????????????????????");
        }


        AdPositionDetailDto adPositionDetailDto = xyAdPositionService.getPositionDetail(positionId);
        if (adPositionDetailDto == null) {
            throw new ServiceException("positionId??????????????????");
        }
        abTestRequest.setPackageName(adPositionDetailDto.getPackageName());

        if (CollectionUtils.isEmpty(flowRate)) {
            throw new ServiceException("flowRate????????????");
        }

        XyStrategyGroup xyStrategyGroup = getDataByPositionAndGroupName(strategyGroupId, positionId, expName);
        if (xyStrategyGroup != null) {
            throw new ServiceException("????????????????????????");
        }

        String versionIds = abTestRequest.getVersionIds();
        String versionOperation = abTestRequest.getVersionOperation();
        if (!versionOperation.equals(XyConstant.OPERA_IN) && !versionOperation.equals(XyConstant.OPERA_NOT_IN) && versionIds !=null && versionIds.contains(",")) {
            throw new ServiceException("versionIds????????????");
        }

        int total = 0;
        int contrast = 0;
        int test = 0;
        //??????????????????
        List<String> AbItemTypeList = Arrays.asList(new String[]{StrategyGroupConstant.STRATEGY_TYPE_TOTAL, StrategyGroupConstant.STRATEGY_TYPE_CONTRAST, StrategyGroupConstant.STRATEGY_TYPE_TEST});
        for (AbItem abItem : flowRate) {
            if (abItem.getType() == null || !AbItemTypeList.contains(abItem.getType())) {
                throw new ServiceException("flowRate type??????");
            }

            if (!abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TOTAL) && abItem.getAbItemId() == null) {
                throw new ServiceException("flowRate abItemId????????????");
            }

            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TOTAL)) {
                total = abItem.getVal();
            }

            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_CONTRAST)) {
                contrast = abItem.getVal();
            }

            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TEST)) {
                test += abItem.getVal();
            }

        }

        if ((contrast + test) > total) {
            throw new ServiceException("???????????????????????????????????????????????????");
        }
    }

    /**
     * ??????ab??????
     *
     * @param abTestRequest
     */
    @Transactional
    public void editAbExp(AbTestRequest abTestRequest, Long abId, List<AbItem> abItemList) throws ServiceException {

        Long strategyGroupId = abTestRequest.getStrategyGroupId();
        Long positionId = abTestRequest.getPositionId();
        String expName = abTestRequest.getExpName();
        List<AbItem> flowRate = abTestRequest.getFlowRate();

        XyStrategyGroup newStrategyGroup = new XyStrategyGroup();
        newStrategyGroup.setId(strategyGroupId);
        newStrategyGroup.setGroupName(expName);
        newStrategyGroup.setStatus(null); //?????????
        newStrategyGroup.setIsDel(null); //?????????
        //??????????????????
        update(newStrategyGroup);


        flowRate = abItemList;
        List<Long> abItemIdList = new ArrayList<>(); //?????????????????????
        for (AbItem abItem : flowRate) {
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TOTAL)) {
                continue;
            }

            Long abItemId = abItem.getAbItemId();
            if (abItemId != null && abItemId > 0L) {
                abItemIdList.add(abItemId);
            }
        }

        //???????????????????????????
        xyStrategyService.updateStrategyClose(strategyGroupId, abItemIdList);

        for (AbItem abItem : flowRate) {
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TOTAL)) {
                continue;
            }

            Long abItemId = abItem.getAbItemId();
            if (abItemId == null || abItemId <= 0L) {
                abItemId = (long)(DateUtils.getCurrentTimestamp()%100000000);
            }

            Integer abRate = abItem.getVal();
            String customField = XyStrategyService.getInitCustomField();

            XyStrategy xyStrategy = new XyStrategy();
            xyStrategy.setAbItemId(abItemId);
            xyStrategy.setStrategyGroupId(newStrategyGroup.getId());
            xyStrategy.setAbRate(abRate);
            xyStrategy.setSortType(StrategyGroupConstant.SORT_TYPE_MANUAL);
            xyStrategy.setStatus(1);
            xyStrategy.setCustomField(customField);
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_CONTRAST)) {
                xyStrategy.setType(StrategyGroupConstant.STRATEGY_TYPE_CONTRAST_VAL);
            }
            if (abItem.getType().equals(StrategyGroupConstant.STRATEGY_TYPE_TEST)) {
                xyStrategy.setType(StrategyGroupConstant.STRATEGY_TYPE_TEST_VAL);
            }
            xyStrategyService.putStrategyWitchAbItemId(xyStrategy);
        }

    }


    /**
     * ??????ab????????????
     *
     * @param strategyGroupId
     * @return
     * @throws ServiceException
     */
    public Map<String, Object> getAbExpDetail(Long strategyGroupId) throws ServiceException {

        if (strategyGroupId == null) {
            throw new ServiceException("strategyGroupId????????????");
        }

        XyStrategyGroup strategyGroupInfo = getDataById(strategyGroupId);
        if (strategyGroupInfo == null) {
            throw new ServiceException("strategyGroupId?????????????????????");
        }

        Long abId = strategyGroupInfo.getAbId();
        if (abId == null || abId == 0L) {
            throw new ServiceException("??????ab??????");
        }


        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> flowRate = new ArrayList<>();
        data.put("positionId", strategyGroupInfo.getPositionId());
        data.put("expName", strategyGroupInfo.getGroupName());
        data.put("layerId", 0);
        data.put("channelType", 1);
        data.put("channelIds", "");
        data.put("versionType", 1);
        data.put("versionIds", "");
        data.put("isNew", 0);
        data.put("flowRate", flowRate);

        List<XyStrategyDto> strategyDtoList = xyStrategyService.getStrategyList(strategyGroupId);
        if (CollectionUtils.isNotEmpty(strategyDtoList)) {
            Map<String, Object> totalInfo  = new HashMap<>();
            totalInfo.put("type", StrategyGroupConstant.STRATEGY_TYPE_TOTAL);
            totalInfo.put("abItemId", 0);
            flowRate.add(totalInfo);
            int total = 0;
            for (XyStrategyDto xyStrategyDto : strategyDtoList) {
                Map<String, Object> item  = new HashMap<>();
                if (xyStrategyDto.getType() == StrategyGroupConstant.STRATEGY_TYPE_CONTRAST_VAL) {
                    item.put("type", StrategyGroupConstant.STRATEGY_TYPE_CONTRAST);
                }

                if (xyStrategyDto.getType() == StrategyGroupConstant.STRATEGY_TYPE_TEST_VAL) {
                    item.put("type", StrategyGroupConstant.STRATEGY_TYPE_TEST);
                }
                item.put("abItemId", xyStrategyDto.getAbItemId());
                item.put("val",xyStrategyDto.getAbRate());
                total += xyStrategyDto.getAbRate();
                flowRate.add(item);
            }
            totalInfo.put("val", total);
        }

        //??????????????????
        Map<String, Object> abFlowMap = Maps.newHashMap();
        ResultMap<Map<String,Object>> resultMap = abPlatFormService.getAbFlowMapByInt(String.valueOf(abId));
        if(resultMap.getCode() != 0){
            throw new ServiceException(resultMap.getMessage());
        }
        abFlowMap = resultMap.getData();
        Map<String,Object> expInfo = (Map<String,Object>) abFlowMap.get("exp_info");

        AbFlowGroupParam param = new AbFlowGroupParam();
        Map<String, Object> conditionMap = new HashMap<>();
        fillCondition(param,expInfo, conditionMap);
        data.put("layerId", MapUtils.getInteger(expInfo,"layer_id"));
//        data.put("channelType", param.getChannel_type());
//        data.put("channelIds", param.getChannel_ids());
//        data.put("versionType", param.getVersion_type());
//        data.put("versionIds", param.getVersion_ids());

        data.put("channelIds", conditionMap.get("channelIds"));
        data.put("channelOperation", conditionMap.get("channelOperation"));
        data.put("versionIds", conditionMap.get("versionIds"));
        data.put("versionOperation", conditionMap.get("versionOperation"));

        data.put("isNew", param.getIs_new());

        return data;
    }

    private void fillCondition(AbFlowGroupParam param, Map<String,Object> expInfo, Map<String, Object> conditionMap) throws ServiceException{
        String condition = org.apache.commons.collections.MapUtils.getString(expInfo, "condition");

        conditionMap.put("versionOperation", "");
        conditionMap.put("versionIds", "");
        conditionMap.put("channelOperation", "");
        conditionMap.put("channelIds", "");

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
                        param.setVersion_type(StrategyGroupConstant.VERSION_TYPE_ONLY);
                        param.setVersion_ids(value);
                    }
                    if(operation.equals("notin")){
                        param.setVersion_type(StrategyGroupConstant.VERSION_TYPE_EX);
                        param.setVersion_ids(value);
                    }

                    conditionMap.put("versionOperation", operation);
                    conditionMap.put("versionIds", value);
                }
                if("father_channel".equals(key)){
                    if(operation.equals("in")){
                        param.setChannel_type(StrategyGroupConstant.CHANNEL_TYPE_ONLY);
                        param.setChannel_ids(value);
                    }
                    if(operation.equals("notin")){
                        param.setChannel_type(StrategyGroupConstant.CHANNEL_TYPE_EX);
                        param.setChannel_ids(value);
                    }

                    conditionMap.put("channelOperation", operation);
                    conditionMap.put("channelIds", value);
                }
                if("is_new".equals(key)){
                    if(operation.equals("in")){
                        //????????????  is_new = -1 ????????????  1:???  0:??? ,ab??????  0??? ???,1 ???
                        if(value == null){
                            param.setIs_new(-1);
                        } else {
                            param.setIs_new(Integer.parseInt(value));
                        }
                    }
                }
            }
        }
        if(param.getIs_new() == null){
            param.setIs_new(-1);
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
            Integer traffic = org.apache.commons.collections.MapUtils.getInteger(test,"traffic");
            maps.add(new AbTraffic(org.apache.commons.collections.MapUtils.getInteger(test,"id"), org.apache.commons.collections.MapUtils.getString(test,"name"),traffic));
            total += traffic;
        }
        maps.add(0,new AbTraffic(-1,"?????????",total));
        param.setRatio(maps);
    }

    /**
     * @Author kangkunhuang
     * @Description ??????????????????????????????,???????????????????????????
     * @Date 2021/2/9
     **/
    @Override
    public void deleteByAppId(Long appId) {
        strategyGroupMapper.deleteByAppId(appId);
        xyAdCodeService.deleteByAppId(appId);
    }
    @Override
    public void deleteByPositionId(Long positionId) {
        strategyGroupMapper.deleteByPositionId(positionId);
        xyAdCodeService.deleteByPositionId(positionId);
    }
}