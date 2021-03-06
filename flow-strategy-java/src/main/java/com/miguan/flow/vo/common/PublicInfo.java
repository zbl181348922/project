package com.miguan.flow.vo.common;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Base64Utils;
import tool.util.StringUtil;

import java.nio.charset.StandardCharsets;

@Slf4j
@Data
public class PublicInfo {
    //distinct_id::channel::package_name::manufacturer::model::os::os_version::screen_height::screen_width::carrier::network_type::is_new::is_new_app::first_visit_time ::imei::oaid::idfa::change_channel::is_login::uuid::creat_time::app_version::last_view::view::openid::longitude::latitude
    private String[] publicInfoArray;

    public PublicInfo(){}

    public PublicInfo(String publicInfoStr) {
        if(StringUtil.isNotBlank(publicInfoStr)) {
            initPublicInfo(publicInfoStr);
            initColumnValue();
        }
    }

    private void initPublicInfo(String publicInfoStr){
        byte[] bytes = Base64Utils.decodeFromString(publicInfoStr);
        publicInfoStr = new String(bytes, StandardCharsets.UTF_8);
        this.publicInfoArray = publicInfoStr.split("::");
    }

    private void initColumnValue(){
        try{
            this.distinctId = getValueByIndex(0);
            this.channel = getValueByIndex(1);
            this.packageName = getValueByIndex(2);
            this.manufacturer = getValueByIndex(3);
            this.model = getValueByIndex(4);
            this.os = getValueByIndex(5);
            this.osVersion = getValueByIndex(6);
            this.screenHeight = Integer.parseInt(getValueByIndex(7));
            this.screenWeight = Integer.parseInt(getValueByIndex(8));
            this.carrier = getValueByIndex(9);
            this.networkType = getValueByIndex(10);
            this.isNew = Boolean.parseBoolean(getValueByIndex(11));
            this.isNewApp = Boolean.parseBoolean(getValueByIndex(12));
            // is_login::uuid::creat_time::app_version::last_view::view::openid::longitude::latitude
            this.firstVisitTime = getValueByIndex(13);
            this.imei = getValueByIndex(14);
            this.oaid = getValueByIndex(15);
            this.idfa = getValueByIndex(16);
            this.changeChannel = getValueByIndex(17);
//        this.isLogin = Boolean.parseBoolean(getValueByIndex(18));
//        this.uuid = getValueByIndex(19);
//        this.createTime = getValueByIndex(20);
//        this.appVersion = getValueByIndex(21);
//        this.lastView = getValueByIndex(22);
//        this.view = getValueByIndex(23);

//            this.openid = getValueByIndex(24);
            this.longtitude = Double.parseDouble(getValueByIndex(18,"0"));
            this.latitude = Double.parseDouble(getValueByIndex(19,"0"));
            this.gpscountry = getValueByIndex(20);
            this.gpsprovince = getValueByIndex(21);
            this.gpscity = getValueByIndex(22);
        } catch (Exception e){
            //log.error("?????????????????????>>{}", JSONObject.toJSONString(publicInfoArray));
        }
    }

    private String getValueByIndex(int idx,String defaultValue){
        if(idx >= publicInfoArray.length){
            return defaultValue;
        }
        String tmp =publicInfoArray[idx];
        if(StringUtils.equals(tmp, "null") || StringUtils.equals(tmp, "(null)")){
            tmp = defaultValue;
        }
        return tmp;
    }

    private String getValueByIndex(int idx){
        return getValueByIndex(idx,"");
    }

    private String distinctId;
    /**
     * ????????????
     */
    private String channel;
    /**
     * ????????????
     */
    private String packageName;
    /**
     * ???????????????
     */
    private String manufacturer;
    /**
     * ??????
     */
    private String model;
    /**
     * ?????????????????????iOS
     */
    private String os;
    /**
     * ???????????????????????????8.1.1
     */
    private String osVersion;
    /**
     * ?????????????????????1920
     */
    private int screenHeight;
    /**
     * ?????????????????????1080
     */
    private int screenWeight;
    /**
     * ???????????????
     */
    private String carrier;
    /**
     * ?????????????????????4G
     */
    private String networkType;
    /**
     * ???????????????
     */
    private boolean isNew;
    /**
     * ????????????????????????
     */
    private boolean isNewApp;
    private String firstVisitTime;
    private String imei;
    private String oaid;
    private String idfa;
    private String changeChannel;
    private boolean isLogin;
    private String uuid;
    private String createTime;
    private String appVersion;
    private String lastView;
    private String view;
    private String openid;
    private double longtitude;
    private double latitude;
    /**
     * ??????
     */
    private String gpscountry;
    /**
     * ???
     */
    private String gpsprovince;
    /**
     * ??????
     */
    private String gpscity;

    public static void main(String[] args) {
        String publicStr="edae0fb489ee1a1c05d95f249a9a0f41::xysp_yingyongbao::com.mg.xyvideo::vivo::vivo X20Plus A::Android::8.1.0::2034::1080::null::wifi::false::false::2020-11-30 15:47:11,2020-11-30 15:47:11::866197036324538::null::null::null::118.196141::24.489745::??????::?????????::?????????";
//        String info = "ZWRhZTBmYjQ4OWVlMWExYzA1ZDk1ZjI0OWE5YTBmNDE6Onh5c3BfeWluZ3lvbmdiYW86OmNvbS5tZy54eXZpZGVvOjp2aXZvOjp2aXZvIFgyMFBsdXMgQTo6QW5kcm9pZDo6OC4xLjA6OjIwMzQ6OjEwODA6Om51bGw6OndpZmk6OmZhbHNlOjpmYWxzZTo6MjAyMC0xMS0zMCAxNTo0NzoxMSwyMDIwLTExLTMwIDE1OjQ3OjExOjo4NjYxOTcwMzYzMjQ1Mzg6Om51bGw6Om51bGw6Om51bGw6OjExOC4xOTYxNDE6OjI0LjQ4OTc0NTo65Lit5Zu9Ojrnpo/lu7rnnIE6OuWOpumXqOW4gg==";
        String info = Base64Utils.encodeToString(publicStr.getBytes());
        System.out.println("????????????" + info);
//        byte[] bytes = Base64Utils.decodeFromString(info);
//        String infoa = new String(bytes, StandardCharsets.UTF_8);
//        System.out.println(infoa);
        PublicInfo publicInfo = new PublicInfo(info);
        System.out.println(publicInfo);

        String publicStr2 = "NjlhODIyODlkMmU2NTQ0N2QzZmRlYTI1ODA4MWI0YWE6Onh5c3BfeWluZ3lvbmdiYW86OmNvbS5tZy54eXZpZGVvOjpPUFBPOjpPUFBPIFI5bTo6QW5kcm9pZDo6NS4xOjoxOTIwOjoxMDgwOjpudWxsOjp3aWZpOjpmYWxzZTo6ZmFsc2U6OjIwMjEtMDEtMTIgMTI6MzU6MjYsMjAyMC0wOS0xMyAyMzo0MDoxNTo6ODYxNjAwMDM3MTM5MzEzOjpudWxsOjpudWxsOjp4eXNwX3lpbmd5b25nYmFvOjoxMTguMTk2MjM0OjoyNC40ODk3NDU6OuS4reWbvTo656aP5bu655yBOjrljqbpl6jluII=";
        byte[] bytes = Base64Utils.decodeFromString(publicStr2);
        String infoa = new String(bytes, StandardCharsets.UTF_8);
        String[] array = infoa.split("::");
        System.out.println("????????????" + infoa);

        PublicInfo publicInfo1 = new PublicInfo(publicStr2);
        System.out.println(publicInfo1);
    }
}
