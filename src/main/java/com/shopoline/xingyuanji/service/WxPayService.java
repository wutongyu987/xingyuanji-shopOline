package com.shopoline.xingyuanji.service;

import com.alibaba.fastjson.JSONObject;

import java.util.Map;

public interface WxPayService {

    /**
     * 微信统一下单
     * @param data
     * @return
     */
    JSONObject unifiedorder(Map<String,String> data);

    /**
     * 支付异步通知
     * @param string
     * @return
     */
    Object payNotify(String string);
}
