package com.shopoline.xingyuanji.service.db1.impl;

import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.shopoline.xingyuanji.Constants;
import com.shopoline.xingyuanji.common.ExceptionEnum;
import com.shopoline.xingyuanji.entity.ProductInfo;
import com.shopoline.xingyuanji.mapper.ProductInfoMapper;
import com.shopoline.xingyuanji.service.db1.IProductInfoService;
import com.shopoline.xingyuanji.service.db1.IShopLogService;
import com.shopoline.xingyuanji.service.db1.IUserAssetService;
import com.shopoline.xingyuanji.utils.RedisUtil;
import com.shopoline.xingyuanji.vo.ProductInfoVO;
import com.shopoline.xingyuanji.vo.UserCoinVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;
import java.util.ListIterator;
import java.util.Random;


/**
 * <p>
 *  商品信息相关实现
 * </p>
 * @author wuty
 * @since 2019-01-09
 */
@Service
public class ProductInfoServiceImpl extends ServiceImpl<ProductInfoMapper, ProductInfo> implements IProductInfoService {

    Logger logger = LoggerFactory.getLogger(this.getClass().getName());

    @Autowired
    private IUserAssetService userAssetService;
    @Autowired
    private IShopLogService shopLogService;

    /**
     * 获取盒子信息
     * @return
     */
    @Override
    public ProductInfoVO getBoxInfo(String type, String kind) {

        ProductInfo productInfo = this.selectOne(new EntityWrapper<ProductInfo>().eq("style",type).eq("kind",kind).
                eq("deleteFlag", Constants.QIYONG).last("Limit 1"));
        Assert.isTrue(productInfo != null, ExceptionEnum.EXCEPTION_4.getDesc());
        ProductInfoVO productInfoVO = new ProductInfoVO();
        productInfoVO.setGoodsname(productInfo.getGoodsname());
        productInfoVO.setPrice(productInfo.getPrice());
        productInfoVO.setCount(productInfo.getProductCount());
        productInfoVO.setImg(productInfo.getImg());
        productInfoVO.setStyle(productInfo.getStyle());
        productInfoVO.setKind(productInfo.getKind());
        productInfoVO.setSocer(productInfo.getSocer());

        return productInfoVO;
    }



    /**
     * 获取盒子抵扣价格
     * @return
     */
    @Override
    public Float getBoxDeductionPrice(String ticketId,String type, String kind,Integer boxCount) {

        ProductInfoVO productInfoVO = getBoxInfo(type,kind);
        // 获取用户猩币信息
        UserCoinVO userCoinVO = userAssetService.quertUserCoin(ticketId);
        // 判断
        Assert.isTrue(userCoinVO.getAmount() != 0,ExceptionEnum.EXCEPTION_5.getDesc());
        Assert.isTrue(userCoinVO.getAmount()>= Constants.XINGBI_DUIHUAN_ZUIDI_COUNT,ExceptionEnum.EXCEPTION_15.getDesc());
        Assert.isTrue(userCoinVO.getAmount() > Constants.BOX_USER_DEDUCTION_XINGBI * boxCount,ExceptionEnum.EXCEPTION_5.getDesc());
        Float deductionPrice = productInfoVO.getPrice() - Constants.XINGBI_DIKOU * boxCount;

        return deductionPrice;
    }

    /**
     * 购盒成功后获取随机商品
     * @param productStyle 0：盒子商品 1：积分商品
     * @return
     */
    @Override
    public ProductInfo getRedomProduct(String ticketId,Integer productStyle,Integer productKind,String randomToken,String UUID) throws Exception {

        // 判断token,防止页面刷新，页面刷新前端会将存下的token删除，取不到VALUE会抛出异常
        String token = RedisUtil.getValue("RandomToken"+ticketId+UUID);
        logger.info("获取TOKEN");

        if(!token.equals(randomToken)){
            throw new Exception(ExceptionEnum.EXCEPTION_17.getDesc());
        }

        // 获取随机产品
        ProductInfo productInfo = baseMapper.getRedomProduct(productStyle,productKind);
        // 判断产品是否是高价值
        if(productInfo.getId() == 110 || productInfo.getId() == 111 || productInfo.getId() == 112 || productInfo.getId() == 113 ||
        productInfo.getId() == 114 || productInfo.getId() == 115 || productInfo.getId() == 105 || productInfo.getId() == 106){

            String COUNT = RedisUtil.getValue("SELLCOUNT");

            if(!COUNT.equals("1")){

                // 查询当天销售总数
                Integer sellCount = shopLogService.selectTodaySellCount();
                // 判断销售量是否达到60个
                if(sellCount >= 60){

                    Random random = new Random();
                    Integer randomNum = random.nextInt(8);
                    String productId = null;
                    switch (randomNum){
                        case 0:
                            productId = "105";
                            break;
                        case 1:
                            productId = "106";
                            break;
                        case 2:
                            productId = "110";
                            break;
                        case 3:
                            productId = "111";
                            break;
                        case 4:
                            productId = "112";
                            break;
                        case 5:
                            productId = "113";
                            break;
                        case 6:
                            productId = "114";
                            break;
                        case 7:
                            productId = "115";
                            break;
                    }
                        // 根据随机数获取商品
                        ProductInfo productInfo1 = this.selectOne(new EntityWrapper<ProductInfo>().eq("id",productId).
                            eq("style",productStyle).eq("kind",productKind).eq("deleteFlag",Constants.QIYONG).last("Limit 1"));
                        productInfo1.setProductCount(productInfo.getProductCount() - 1);
                        this.updateById(productInfo);
                        RedisUtil.setValueDAY("SELLCOUNT","1");

                        return productInfo1;
                    }
                }
            }
        productInfo.setProductCount(productInfo.getProductCount() - 1);
        this.updateById(productInfo);

        return productInfo;
    }

    @Override
    public List<ProductInfo> getShopList() {

        List<ProductInfo> productInfo = this.selectList(new EntityWrapper<ProductInfo>().eq("style", Constants.JIFEN_PRODUCT).
                eq("kind",Constants.JIFEN_PRODUCT).eq("deleteFlag",Constants.QIYONG).orderBy("price",false));

        return productInfo;
    }

    @Override
    public ProductInfo getShopProductInfo(String productId) throws Exception {

        ProductInfo productInfo = this.selectOne(new EntityWrapper<ProductInfo>().eq("id",productId).last("Limit 1"));

        if(productInfo.getProductCount().equals(Constants.NULL)){
            throw new Exception(ExceptionEnum.EXCEPTION_19.getDesc());
        }
        productInfo.setImg(productInfo.getImg());

        return productInfo;
    }

    @Override
    public Object getBoxImg(String boxId) {

        String boxImgInfo = "\\boxImg\\" + boxId +".png";
        return boxImgInfo;
    }

    @Override
    public void setImg() {

        List<ProductInfo> productInfo = this.selectList(new EntityWrapper<>());
        ListIterator<ProductInfo> iterator = productInfo.listIterator();
        while(iterator.hasNext()){
            ProductInfo productInfo1 = iterator.next();
            productInfo1.setImg(productInfo1.getGoodsname()+".png");
            productInfo1.setShopImg(productInfo1.getGoodsname()+".png");
            this.updateById(productInfo1);
        }
    }


}
