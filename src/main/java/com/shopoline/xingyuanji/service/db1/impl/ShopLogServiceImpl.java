package com.shopoline.xingyuanji.service.db1.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.toolkit.IdWorker;
import com.shopoline.xingyuanji.Constants;
import com.shopoline.xingyuanji.WxConfig;
import com.shopoline.xingyuanji.common.ExceptionEnum;
import com.shopoline.xingyuanji.entity.*;
import com.shopoline.xingyuanji.mapper.ShopLogMapper;
import com.shopoline.xingyuanji.model.PayModel;
import com.shopoline.xingyuanji.model.RespResultModel;
import com.shopoline.xingyuanji.model.SendHomeModel;
import com.shopoline.xingyuanji.model.ShopLogModel;
import com.shopoline.xingyuanji.service.db1.*;
import com.shopoline.xingyuanji.utils.*;
import com.shopoline.xingyuanji.vo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 *  购物记录，业务相关实现
 * </p>
 * @author wuty
 * @since 2019-01-10
 */
@Service
public class ShopLogServiceImpl extends ServiceImpl<ShopLogMapper, ShopLog> implements IShopLogService {

    Logger logger = LoggerFactory.getLogger(this.getClass().getName());

    @Autowired
    private IUserInfoService userInfoService;
    @Autowired
    private IUserAssetService userAssetService;
    @Autowired
    private IShopLogService shopLogService;
    @Autowired
    private IProductInfoService productInfoService;
    @Autowired
    private WxPayService wxPayService;
    @Autowired
    private IUserAddressService userAddressService;

    private Integer expire = 30 * 60 * 1000;//30分钟

    /**
     * 微信统一下单
     * @param ticketId
     * @return
     */
    @Override
    public Object setOrder(String ticketId,PayModel payModel){

        // 获取用户信息
        UserInfo userInfo = userInfoService.getDB1UserInfo(ticketId);

        // 初始HASHMAP容量防止RESIZE
        int capacity = (int)(15/0.75)+1;
        // 写入微信支付数据
        ConcurrentHashMap<String,String> payInfoMap = new ConcurrentHashMap<>(capacity);
        // 商品的简单描述
        payInfoMap.put("body","猩愿盒");
        // 订单号，32个字符以内，只能是数字，字母
        String tradeNum = IdWorker.get32UUID();
        payInfoMap.put("out_trade_no",tradeNum);
        // 商品详细描述，对于使用单品优惠的商户，改字段必须按照规范上传
        payInfoMap.put("detail", "猩愿盒");
        // 商品id，扫码支付必传。
        payInfoMap.put("product_id", ""+ 0);
        // 订单总金额，单位为分
        // payInfoMap.put("total_fee", ""+Integer.valueOf(payModel.getTotalFee()) * 100);
        // 订单总金额，单位为分测试
        payInfoMap.put("total_fee", payModel.getTotalFee());
        logger.info("total_fee:"+payInfoMap.get("total_fee"));
        // 币种
        payInfoMap.put("fee_type", "CNY");
        // 终端ip
        payInfoMap.put("spbill_create_ip", "192.168.1.101");
        // 异步接收微信支付结果的回调通知
        payInfoMap.put("notify_url", WxConfig.NOTIFYURL);
        // 交易类型，JSAPI--公众号支付，NATIVE--原生扫码支付，APP--app支付，MWEB--H5支付
        payInfoMap.put("trade_type", "JSAPI");
        // 签名加密方式，默认是MD5
        payInfoMap.put("sign_type", "MD5");
        // 交易类型位公众号支付时必须
        String openId = GetOpenId.getOpenId(ticketId);
        payInfoMap.put("openid", openId);
        // no_credit--限制用户不能使用信用卡支付
        payInfoMap.put("limit_pay", "no_credit");
        // 交易有效时间
        Date now = new Date();
        // 交易起始时间，格式为yyyyMMddHHmmss,或者基于北京时间的时间戳
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        payInfoMap.put("time_start", simpleDateFormat.format(now));
        // 交易结束时间，即订单有效时间
        Date wil = new Date(now.getTime() + expire);
        payInfoMap.put("time_expire", simpleDateFormat.format(wil));
        // 上报实际门店信息
        payInfoMap.put("scene_info", "{\"store_info\" : {\n" +
                "\"id\": \"1\",\n" +
                "\"name\": \"心愿机\",\n" +
                "\"area_code\": \"440305\",\n" +
                "\"address\": \"心愿有限公司\" }}\n");
        JSONObject jsonObject = wxPayService.unifiedorder(payInfoMap);
        // token
        String randomToken = IdWorker.get32UUID();
        String UUID = IdWorker.get32UUID();
            // 向Redis中写入交易数据在后续接口中获取写入数据库
            // 写入微信支付订单号
            RedisUtil.setValue(ticketId+"tradeNum",tradeNum);
            // 写入支付金额
            RedisUtil.setValue(ticketId+"totalFee", String.valueOf(payModel.getTotalFee()));
            // 向Redis中写入交易的TOKEN，防止盗刷
            RedisUtil.setValueSecond("RandomToken"+ticketId+UUID,randomToken);
            logger.info("写入TOKEN"+"\t"+userInfo.getNickName());
        jsonObject.put("randomToken",randomToken);
        jsonObject.put("UUID",UUID);

        logger.info("<-PAY_HISTORY->\t"+"NickName："+userInfo.getNickName()+"\tTradeNum："+tradeNum+"\tTotalFee："+payModel.getTotalFee()+"\tUserOpenId："+openId+
                "\tTime："+payInfoMap.get("time_expire"));

        return jsonObject;
    }

    /**
     * 交易成功后业务,获取随机商品
     * @param ticketId
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AfterPaySuccessVO afterPaySuccess(String ticketId, String useXingBi, String isPay, String randomToken, String UUID) throws Exception {

        // 获取用户信息
        UserInfo userInfo = userInfoService.getDB1UserInfo(ticketId);

        // 获取随机商品
        ProductInfo productInfo = productInfoService.getRedomProduct(ticketId,Integer.parseInt(Constants.HEZI_PRODUCT),
                    Integer.parseInt(Constants.HEZI_PRODUCT),randomToken,UUID);
        AfterPaySuccessVO afterPaySuccessVO = new AfterPaySuccessVO();
        afterPaySuccessVO.setProductInfo(productInfo);

        // 扣除猩币
        if(useXingBi.equals("1") || useXingBi == "1"){
            userAssetService.setUseXingBi(userInfo);
        }

        // 从Redis中获取微信JSAPI付款后存入的交易数据
            // 获取支付订单号
            String tradeNum = RedisUtil.getValue(ticketId + "tradeNum");
            // 获取支付金额
            String totalFee = RedisUtil.getValue(ticketId + "totalFee");

            // 将商品写入ShopLog表
            ShopLog shopLog = new ShopLog();
            shopLog.setId(IdWorker.get32UUID());
            afterPaySuccessVO.setShopLogId(shopLog.getId());
            shopLog.setUserId(userInfo.getUserId());
            String openId = GetOpenId.getOpenId(ticketId);
            shopLog.setOpenId(openId);
            shopLog.setGoodsId(productInfo.getId());
            shopLog.setBoxId(Constants.BOX);
            shopLog.setEditBy(Constants.ADMIN);
            shopLog.setEditTime(new Date());
            shopLog.setDeleteFlag(Constants.QIYONG);
            shopLog.setExpress(Constants.KUAIDI_FROM_ZANDING);
            shopLog.setOutTradeNo(tradeNum);
            shopLog.setIsPay(isPay);
            shopLog.setTotalFee(totalFee);
            shopLog.setIsDeliver(Constants.WEI_FA_HUO);
            shopLogService.insert(shopLog);

            // 写入购盒奖励
            UserAsset userAsset = new UserAsset();
            Integer XingBi = XingBiPriceUtils.getBuyBoxPrice(1);
            userAsset.setId(IdWorker.get32UUID());
            userAsset.setUserId(userInfo.getUserId());
            userAsset.setAmount(XingBi);
            userAsset.setAmountTye(Constants.XINGBI);
            userAsset.setEditTime(new Date());
            userAsset.setEditBy(Constants.ADMIN);
            userAsset.setDeltFlag(Constants.QIYONG);
            userAsset.setOpenId(openId);
            userAssetService.insert(userAsset);

            // 流程结束删除Redis中存入的交易TOKEN
            RedisUtil.delete(ticketId + "tradeNum");
            RedisUtil.delete(ticketId + "totalFee");
            logger.info("删除TOKEN");

            //记录Log
            logger.info("<-AFTER_PAY->\t"+"UserName："+userInfo.getNickName()+"\tProductId："+productInfo.getId()+"\tProductName："+
                    productInfo.getGoodsname()+"\tTotalFee："+totalFee+"\tAssert："+XingBi+"\tDate："+userAsset.getEditTime());

        return afterPaySuccessVO;
    }

    @Override
    public List<ShopLogModel> getShopLog(String ticketId) {

        String openId = GetOpenId.getOpenId(ticketId);
        // 根据OpenId获取用户购物记录
        List<ShopLogModel> shopLogModelList = baseMapper.getShopList(openId);

        ListIterator<ShopLogModel> iterator = shopLogModelList.listIterator();
        while(iterator.hasNext()){
            ShopLogModel shopLogModel = iterator.next();
            if(shopLogModel.getExpress().equals("0")){
                shopLogModel.setExpress("已邮回家");
                shopLogModel.setExpressCode("0");
            }else if(shopLogModel.getExpress().equals("1")){
                shopLogModel.setExpress("送好友");
                shopLogModel.setExpressCode("1");
            }else if(shopLogModel.getExpress().equals("2")){
                shopLogModel.setExpress("换猩币");
                shopLogModel.setExpressCode("2");
            }else if(shopLogModel.getExpress().equals("3")){
                shopLogModel.setExpress("盒子在等你哦");
                shopLogModel.setExpressCode("3");
        }

            if (shopLogModel.getIsPay() == null || shopLogModel.getIsPay().equals("0")){
                shopLogModel.setIsPay("未支付");
            }else if(shopLogModel.getIsPay().equals("1")){
                shopLogModel.setIsPay("已支付");
            }

            if(shopLogModel.getStyle().equals("0")){
                shopLogModel.setStyle("盒子商品");
                shopLogModel.setStyleCode("0");
                shopLogModel.setImg(shopLogModel.getImg());
            }else if(shopLogModel.getStyle().equals("1")){
                shopLogModel.setStyle("积分商品");
                shopLogModel.setStyleCode("1");
                shopLogModel.setImg("/productPic/"+shopLogModel.getImg());
            }
        }

        return shopLogModelList;
    }


    /**
     * 查看订单记录商品详情
     * @param ticketId
     * @return
     */
    @Override
    public ShopLogInfoVO getShopLogInfo(String ticketId) {

        ShopLogInfoVO shopLogInfoVO = new ShopLogInfoVO();
        // 获取购买记录

        String openId = GetOpenId.getOpenId(ticketId);
        ShopLog shopLog = this.selectOne(new EntityWrapper<ShopLog>().eq("openId",openId).eq("deleteFlag",Constants.QIYONG).last("Limit 1"));

        // 获取商品信息
        ProductInfo productInfo = productInfoService.selectOne(new EntityWrapper<ProductInfo>().eq("id",shopLog.getGoodsId()).
                eq("deleteFlag",Constants.QIYONG).last("Limit 1"));
        shopLogInfoVO.setGoodsname(productInfo.getGoodsname());
        shopLogInfoVO.setPrice(productInfo.getPrice());
        shopLogInfoVO.setImg(productInfo.getImg());

        if(productInfo.getStyle().equals("0")){
            shopLogInfoVO.setStyle("盒子商品");
        }else if(productInfo.getStyle().equals("1")){
            shopLogInfoVO.setStyle("积分商品");
        }

        if(productInfo.getKind().equals("0")){
            shopLogInfoVO.setKind("开盒商品");
        }else if(productInfo.getKind().equals("1")){
            shopLogInfoVO.setKind("积分商品");
        }

        if(shopLog.getIsPay() == null ||shopLog.getIsPay().equals("0")){
            shopLogInfoVO.setIsPay("未支付");
        }else{
            shopLogInfoVO.setIsPay("已支付");
        }
        shopLogInfoVO.setSocer("积分价值："+productInfo.getSocer());
        return shopLogInfoVO;
    }

    /**
     * 请求顺丰下单
     * @param ticketId
     * @return
     */
    @Override
    public void sendHome(String ticketId,String productId,String shopLogId) throws Exception{


        // 获取用户信息
        UserInfo userInfo = userInfoService.getDB1UserInfo(ticketId);
        // 获取用户默认地址
        UserAddress userAddress = userAddressService.selectOne(new EntityWrapper<UserAddress>().eq("userId",userInfo.getUserId()).
                eq("def","1").eq("deleteFlag",Constants.QIYONG).last("Limit 1"));

        SendHomeModel sendHomeModel = new SendHomeModel();
        sendHomeModel.setName(userAddress.getName());
        sendHomeModel.setPhone(String.valueOf(userAddress.getPhone()));
        sendHomeModel.setArea(userAddress.getArea());
        sendHomeModel.setProvince(userAddress.getProvince());
        sendHomeModel.setCity(userAddress.getCity());
        sendHomeModel.setAddress(userAddress.getAddress());
        sendHomeModel.setProductId(productId);

        // 获取产品详情
       ProductInfo productInfo = productInfoService.getShopProductInfo(productId);

            // 从redis中获取邮费支付订单号
            String ZIPTradeNum = RedisUtil.getValue(ticketId+"tradeNum");
            String totalFee = RedisUtil.getValue(ticketId+"totalFee");

            //写入相关记录
            String openId = GetOpenId.getOpenId(ticketId);

            ShopLog shopLog = this.selectOne(new EntityWrapper<ShopLog>().eq("id", shopLogId).eq("openId", openId).eq("goodsId", productInfo.getId()).
                        eq("express", Constants.KUAIDI_FROM_ZANDING).last("Limit 1"));
            shopLog.setExpress(Constants.YOU_HUI_JIA);
            shopLog.setZIPAmount(totalFee);
            shopLog.setZIPOutTradeNo(ZIPTradeNum);
            shopLog.setAddressId(userAddress.getId());
            shopLog.setUpdateTime(new Date());
            this.updateById(shopLog);

            logger.info("<-SEND_HOME->\t"+"NickName："+userInfo.getNickName()+"\tRealName："+userAddress.getName()+"\tTradeNo："+shopLog.getTradeNo()+
                    "\tZIPAmount："+shopLog.getZIPAmount()+"\tZIPOutTradeNo:"+ZIPTradeNum+"\tDate："+new Date());

            // 删除缓存中的TOKEN
            RedisUtil.delete(ticketId+"tradeNum");
            RedisUtil.delete(ticketId+"totalFee");
    }

    @Override
    public Object deleteOrder(String ticketId,String orderId) {

        String deleteOrderResult = SFUtils.exitOrder(orderId);

        return deleteOrderResult;
    }

    @Override
    public Object getZIPAmount(String ticketId) {

        // 获取用户信息
        UserInfo userInfo = userInfoService.getDB1UserInfo(ticketId);

        // 获取地址信息
        UserAddress userAddress = userAddressService.selectOne(new EntityWrapper<UserAddress>().eq("userId",userInfo.getUserId()).
                eq("deleteFlag",Constants.QIYONG).last("Limit 1"));
        SendHomeModel sendHomeModel = new SendHomeModel();
        sendHomeModel.setProvince(userAddress.getProvince());
        sendHomeModel.setCity(userAddress.getCity());

        // 计算邮费
        Integer ZIPAmount = CheckZIPAmount.checkAmount(sendHomeModel);

        return ZIPAmount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object buyXingBiProduct(String ticketId, String productId) throws Exception{

        UserCoinVO userCoinVO = userAssetService.quertUserCoin(ticketId);
        ProductInfo productInfo = productInfoService.getShopProductInfo(productId);
        UserInfo userInfo = userInfoService.getDB1UserInfo(ticketId);

        if(userCoinVO.getAmount() < Integer.parseInt(productInfo.getSocer())){
            throw new Exception(ExceptionEnum.EXCEPTION_5.getDesc());
        }
        if(productInfo.getProductCount().equals(Constants.NULL)){
            throw new Exception(ExceptionEnum.EXCEPTION_4.getDesc());
        }

            //扣除用户猩币
            userAssetService.dedUserXingbi(productInfo,userInfo);
            //写入消费记录
            ShopLog shopLog = new ShopLog();
            shopLog.setId(IdWorker.get32UUID());
            shopLog.setUserId(userInfo.getUserId());
            shopLog.setOpenId(userInfo.getOpenId());
            shopLog.setGoodsId(Integer.valueOf(productInfo.getId()));
            shopLog.setBoxId(Constants.SHOP);
            shopLog.setEditBy(Constants.ADMIN);
            shopLog.setEditTime(new Date());
            shopLog.setDeleteFlag(Constants.QIYONG);
            shopLog.setExpress(3);
            shopLog.setIsDeliver(Constants.WEI_FA_HUO);
            this.insert(shopLog);

            // 减去商品数量
            productInfo.setProductCount(productInfo.getProductCount() - 1);
            productInfoService.updateById(productInfo);

            BuyShopProductVO buyShopProductVO = new BuyShopProductVO();
            buyShopProductVO.setZIPAmount("1");
            buyShopProductVO.setProductId(productId);

            logger.info("<-BUY_XINGBI_PRODUCT->\t"+"NickName："+userInfo.getNickName()+"\tOpenId："+userInfo.getOpenId()+"\tProductId："+productInfo.getId()+
                    "\tProductName："+productInfo.getGoodsname()+"\tDelUserAmount："+"-"+productInfo.getSocer()+"\tDate："+new Date());

            return buyShopProductVO;
    }



    /**
     * 扣除猩币
     * @param ticketId
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductXingBi(String ticketId) {

        UserInfo userInfo = userInfoService.getDB1UserInfo(ticketId);
        // 扣除猩币
        userAssetService.setUseXingBi(userInfo);
    }

    /**
     * 查询当天线上销售数量
     * @return
     */
    @Override
    public Integer selectTodaySellCount() {

        return baseMapper.selectTodaySellCount(Constants.BOX_ID_BOX,Constants.ISPAY);
    }

    /**
     * 查询物流信息
     * @param ticketId
     * @param productId
     * @return
     */
    @Override
    public LogisticInformationVO getLogisticInformation(String ticketId, String productId, String tradeNo) throws Exception {

        //获取商品信息
        ProductInfo productInfo = productInfoService.selectOne(new EntityWrapper<ProductInfo>().eq("id",productId).last("Limit 1"));

        // 获取物流信息
        List<RespResultModel> respResultModelList= SFUtils.getOrderStatus(tradeNo);
        LogisticInformationVO logisticInformationVO = new LogisticInformationVO();
        logisticInformationVO.setLogisticInformation(respResultModelList);
        logisticInformationVO.setProductName(productInfo.getGoodsname());
        logisticInformationVO.setPrice(String.valueOf(productInfo.getPrice()));

        return logisticInformationVO;
    }


}
