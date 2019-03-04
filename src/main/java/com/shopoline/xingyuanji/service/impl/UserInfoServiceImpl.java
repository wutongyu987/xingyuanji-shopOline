package com.shopoline.xingyuanji.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.toolkit.IdWorker;
import com.shopoline.xingyuanji.Constants;
import com.shopoline.xingyuanji.WxConfig;
import com.shopoline.xingyuanji.entity.UserInfo;
import com.shopoline.xingyuanji.mapper.UserInfoMapper;
import com.shopoline.xingyuanji.model.SignModel;
import com.shopoline.xingyuanji.service.IUserAssetService;
import com.shopoline.xingyuanji.service.IUserInfoService;
import com.shopoline.xingyuanji.utils.CheckTicketIdUtil;
import com.shopoline.xingyuanji.utils.EmojiConvertUtil;
import com.shopoline.xingyuanji.utils.GetOpenId;
import com.shopoline.xingyuanji.utils.OkhttpUtil;
import com.shopoline.xingyuanji.vo.LoginVO;
import com.shopoline.xingyuanji.vo.UserCoinVO;
import com.shopoline.xingyuanji.vo.UserInfoVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * <p>
 * 用户信息
 服务实现类
 * </p>
 *
 * @author wuty
 * @since 2019-01-09
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

   Logger logger = LoggerFactory.getLogger(this.getClass().getName());

    @Autowired
    private IUserAssetService userAssetService;


    /*
    * 登陆
    * */
    @Override
    public Object WXLogin(String code) throws Exception {

        String url = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + Constants.APPID
                + "&secret=" + Constants.SECRET + "&code=" + code
                + "&grant_type=authorization_code";
        String str = OkhttpUtil.get(url);
        System.out.println(str);
        JSONObject jsonObject = JSON.parseObject(str);
        String accessToken = jsonObject.getString("access_token");
        String openId = jsonObject.getString("openid");

        UserInfo userInfo = this.selectOne(new EntityWrapper<UserInfo>().eq("openId",openId));
        if (userInfo==null) {
            //拉取用户信息
            url = "https://api.weixin.qq.com/sns/userinfo?access_token=" + accessToken + "&openid=" + openId + "&lang=zh_CN";
            str = OkhttpUtil.get(url);
            System.out.println("wxuser:"+str);
            jsonObject = JSON.parseObject(str);

            UserInfo user = new UserInfo();
            user.setUserId(IdWorker.get32UUID());
            String nickName = EmojiConvertUtil.emojiConvert(jsonObject.getString("nickname"));
            user.setNickName(nickName);
            user.setOpenId(openId);
            user.setSex(Integer.parseInt(jsonObject.getString("sex")));
            user.setHeadImgUrl(jsonObject.getString("headimgurl"));
            user.setEditTime(new Date());
            user.setUpdateTime(new Date());
            user.setEditBy("admin");
            user.setDeleteFlag(1);
            this.insert(user);
        }

        CheckTicketIdUtil checkTicketIdUtil = new CheckTicketIdUtil();
        //登陆将openId写入REDIS
        LoginVO loginVO = checkTicketIdUtil.checkTicketId(openId);

        logger.info("<-USER_LOGIN->\t"+"UserName："+userInfo.getNickName()+"\tUserOpenId："+userInfo.getOpenId()+"\tDate："+new Date());

        return loginVO;
    }

    @Override
    public UserInfoVO getUserInfo(String ticketId) throws Exception {

        String openId = GetOpenId.getOpenId(ticketId);
        UserInfo userInfo = this.selectOne(new EntityWrapper<UserInfo>().eq("openid",openId).eq("deleteFlag",Constants.QIYONG));
        UserCoinVO userCoinVO = userAssetService.quertUserCoin(ticketId);
        String sex;
        if(userInfo.getSex() == 1){
            sex = "男";
        }else{
            sex = "女";
        }
        UserInfoVO userInfoVO = new UserInfoVO();
        userInfoVO.setUserId(userInfo.getUserId());
        String nickName = EmojiConvertUtil.emojiRecoveryTo(userInfo.getNickName());
        userInfoVO.setNickName(nickName);
        userInfoVO.setOpenId(openId);
        userInfoVO.setSex(sex);
        userInfoVO.setHeadImgUrl(userInfo.getHeadImgUrl());
        userInfoVO.setXingBiAmount(String.valueOf(userCoinVO.getAmount()));
        return userInfoVO;
    }

    @Override
    public SignModel getSign() {
        String url = "https://www.xingyuanji.com/dist";
        SignModel signModel = WxConfig.getSign(url);
        return signModel;
    }


}
