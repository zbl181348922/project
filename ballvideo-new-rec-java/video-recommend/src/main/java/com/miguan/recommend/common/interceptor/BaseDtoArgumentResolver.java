package com.miguan.recommend.common.interceptor;

import com.alibaba.fastjson.JSONObject;
import com.miguan.recommend.bo.BaseDto;
import com.miguan.recommend.bo.PublicInfo;
import com.miguan.recommend.bo.UserFeature;
import com.miguan.recommend.common.aop.Base;
import com.miguan.recommend.common.constants.ABConstant;
import com.miguan.recommend.common.constants.SymbolConstants;
import com.miguan.recommend.common.util.ABTestUtils;
import com.miguan.recommend.common.util.SpringUtil;
import com.miguan.recommend.service.ABTestService;
import com.miguan.recommend.service.recommend.FeatureService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.springframework.util.CollectionUtils.isEmpty;

@Slf4j
@Component
public class BaseDtoArgumentResolver implements HandlerMethodArgumentResolver {

    public final static String DEFAULT_GROUP = "1";
    public final static String EMPTY_GROUP = "ungrouped";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Base.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {

        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        String publicInfo = request.getParameter("publicInfo");
        String abExp = request.getParameter("abExp");
        String abIsol = request.getParameter("abIsol");
        String ip = request.getParameter("ip");
        log.debug("BaseDtoArgumentResolver publicInfo>>{}, ip>>{}", publicInfo, ip);

        PublicInfo publicBo = new PublicInfo(publicInfo);
        log.debug("BaseDtoArgumentResolver publicBo>>{}", JSONObject.toJSONString(publicBo));
        BeanFactory factory = WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
        FeatureService featureService = (FeatureService) factory.getBean("featureService");
        UserFeature userFeature = featureService.initUserFeature(publicBo.getUuid(), ip);

        boolean isABtest = ABTestUtils.isABTestUser(publicBo.getUuid());
        boolean isSubABtest = ABTestUtils.checkSubABTest(publicBo.getUuid());
        boolean ABChannel = ABTestUtils.isABChannel(StringUtils.isEmpty(publicBo.getChangeChannel()) ? publicBo.getChannel() : publicBo.getChangeChannel());
        boolean ABUUId = ABTestUtils.isABUUid(publicBo.getUuid());

        ABTestService abTestService = SpringUtil.getBean(ABTestService.class);
        // ????????????????????????????????????
        Map<String, String> firstFlushGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_NEW_USER_VIDEO, ABConstant.JAVA_TYPE);
        log.info("???????????????????????????>>{}", firstFlushGroupMap);
        String firstFlushGroup = this.getExperimentalGroup(abExp, firstFlushGroupMap, DEFAULT_GROUP);
        log.info("{} ????????????????????????{}???", publicBo.getUuid(), firstFlushGroup);

        // ???????????????????????????????????????
//        Map<String, String> channelConsistencyGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_VIDEO_DIFFERENCES, ABConstant.JAVA_TYPE);
//        log.info("????????????????????????????????????>>{}", channelConsistencyGroupMap);
        String channelConsistencyGroup = "1";
//        if (this.isContainsExperimentalGroup(abExp, channelConsistencyGroupMap.get("2"))) {
//            channelConsistencyGroup = "2";
//            log.info("{} ?????????????????????????????????2???", publicBo.getUuid());
//        } else {
//            log.info("{} ?????????????????????????????????1???", publicBo.getUuid());
//        }

        // CVR????????????
        Map<String, String> cvrGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_PREDICT_3, ABConstant.JAVA_TYPE);
        String cvrGroup = this.getExperimentalGroup(abExp, cvrGroupMap, DEFAULT_GROUP);

        //????????????????????????????????????
//        Map<String, String> isLowAvgGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_TOP_RECOMMEND, ABConstant.JAVA_TYPE);
        Integer isLowAvg = null;
//        if (this.isContainsExperimentalGroup(abExp, isLowAvgGroupMap.get("2"))) {
//            isLowAvg = 0;
//            log.info("{} ??????????????????????????????2???", publicBo.getUuid());
//        }

        // ????????????????????????
        Map<String, String> embeddingGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_VECTOR_RECOMMEND, ABConstant.JAVA_TYPE);
        String embeddingGroup = this.getExperimentalGroup(abExp, embeddingGroupMap, DEFAULT_GROUP);
        log.info("{} ????????????????????????{}", publicBo.getUuid(), embeddingGroup);

        // 98???????????????????????????
//        Map<String, String> video98GroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_98VIDEO, ABConstant.AB_TYPE);
        boolean video98Group = false;
//        if (this.isContainsExperimentalGroup(abExp, video98GroupMap.get("2"))) {
//            video98Group = true;
//            log.info("{} ?????????98?????????????????????", publicBo.getUuid());
//        } else {
//            log.info("{} ?????????98?????????????????????", publicBo.getUuid());
//        }

        // CTR????????????
        Map<String, String> ctrMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.CTR_AB_TEST, ABConstant.JAVA_TYPE);
        String ctrGroup = this.getExperimentalGroup(abExp, ctrMap, DEFAULT_GROUP);

        // ??????????????????????????????
        Map<String, String> relevantGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.REAL_TIME_RELEVANT_RECOMMEND, ABConstant.JAVA_TYPE);
        String relevantGroup = this.getExperimentalGroup(abExp, relevantGroupMap, DEFAULT_GROUP);
        log.info("{} ??????????????????????????????{}???", publicBo.getUuid(), relevantGroup);

        // ??????????????????AB??????
//        Map<String, String> appointVideoGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.XYSP_NEW_USER_APPOINT_VIDEO_02, ABConstant.AB_TYPE);
        String appointVideoGroup = DEFAULT_GROUP;
//        String appointVideoGroup = this.getExperimentalGroup(abExp, appointVideoGroupMap, DEFAULT_GROUP);
//        log.info("{} ???????????????????????????????????????{}???", publicBo.getUuid(), appointVideoGroup);

        // ?????????????????????????????????
        Map<String, String> interstLabelGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.APP_XYSP_INTEREST_LABEL, ABConstant.AB_TYPE);
        String interstLabelGroup = this.getExperimentalGroup(abExp, interstLabelGroupMap, DEFAULT_GROUP);
        log.info("{} ???????????????????????????????????????{}???", publicBo.getUuid(), interstLabelGroup);

        // ????????????
        Map<String, String> specialGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.XYSP_STRONGCONSISTENCY, ABConstant.AB_TYPE);
        String specialGroup = this.getExperimentalGroup(abExp, specialGroupMap, EMPTY_GROUP);
        log.info("{} ????????????????????????{}???", publicBo.getUuid(), specialGroup);

        // ???????????????????????????
        Map<String, String> oldUserOptimizeGroupMap = abTestService.getABTestGroupInfoByExpKey(ABConstant.NEW_OLD_USER_REC_OPTIMIZAION, ABConstant.AB_TYPE);
        String oldUserOptimizeGroup = this.getExperimentalGroup(abExp, oldUserOptimizeGroupMap, DEFAULT_GROUP);
        log.info("{} ????????????????????????????????????{}???", publicBo.getUuid(), oldUserOptimizeGroup);

        return new BaseDto(publicBo.getDistinctId(), publicBo.getUuid(), abExp, abIsol, publicBo, userFeature,
                isABtest, isSubABtest, ABChannel, ABUUId, 0L, 0L, firstFlushGroup,
                channelConsistencyGroup, cvrGroup, isLowAvg, embeddingGroup, video98Group, ctrGroup, relevantGroup,
                appointVideoGroup, interstLabelGroup, specialGroup, oldUserOptimizeGroup);
    }

    /**
     * ????????????????????????ab_exp?????????????????????
     *
     * @param abExp             ??????????????????ab_exp
     * @param experimentalGroup ?????????
     * @return
     */
    private boolean isContainsExperimentalGroup(String abExp, String experimentalGroup) {
        if (isEmpty(abExp) || isEmpty(experimentalGroup)) {
            return false;
        }
        return StringUtils.containsAny(abExp, experimentalGroup.split(SymbolConstants.comma));
    }

    private String getExperimentalGroup(String abExp, Map<String, String> experimentalGroupInfoMap, String defaultGroup) {
        if (isEmpty(experimentalGroupInfoMap)) {
            return defaultGroup;
        }

        Set<String> groupSet = experimentalGroupInfoMap.keySet();
        for (String experimentalGroup : groupSet) {
            if (StringUtils.containsAny(abExp, experimentalGroupInfoMap.get(experimentalGroup).split(SymbolConstants.comma))) {
                return experimentalGroup;
            }
        }
        return defaultGroup;
    }

}
