package com.hmall.cart.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;

import com.hmall.cart.domain.dto.CartFormDTO;
import com.hmall.cart.domain.po.Cart;
import com.hmall.cart.domain.vo.CartVO;
import com.hmall.cart.mapper.CartMapper;
import com.hmall.cart.service.ICartService;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {
    private final ItemClient itemClient;
    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();

        // 2.判断是否已经存在
        if(checkItemExists(cartFormDTO.getItemId(), userId)){
            // 2.1.存在，则更新数量
            baseMapper.updateNum(cartFormDTO.getItemId(), userId);
            return;
        }
        // 2.2.不存在，判断是否超过购物车数量
        checkCartsFull(userId);

        // 3.新增购物车条目
        // 3.1.转换PO
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        // 3.2.保存当前用户
        cart.setUserId(userId);
        // 3.3.保存到数据库
        save(cart);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        // 1.查询我的购物车列表
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId, UserContext.getUser()).list();
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }

        // 2.转换VO
        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);
        // 3.处理VO中的商品信息
        handleCartItems(vos);

        // 4.返回
        return vos;
    }
    //基于openFeign的调用
    
    private void handleCartItems(List<CartVO> vos) {
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
        if(itemIds.isEmpty())return;
        //远程调用兼负载均衡
        final List<ItemDTO> itemDTOS = itemClient.queryItemById(itemIds);
        Map<Long, ItemDTO> itemMap = itemDTOS.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    ///基于nacos的远程调用（初级）
//
//    private void handleCartItems(List<CartVO> vos) {
//                // 1.获取商品id
//        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
//        //根据服务的名称获取服务的实例
//       List<ServiceInstance> instances;
//       if((instances= discoveryClient.getInstances("item-service"))==null)
//           return;
//        //手写负载均衡，挑选一个实例
//        final ServiceInstance serviceInstance = instances.get(RandomUtil.randomInt(instances.size()));
//                final ResponseEntity<List<ItemDTO>> ids = restTemplate.exchange(
//                serviceInstance.getUri()+"/items?ids={ids}",
//                HttpMethod.GET,
//                null,
//                new ParameterizedTypeReference<>() {
//                },
//                Map.of("ids", CollUtil.join(itemIds, ","))
//        );
//
//        if (!ids.getStatusCode().is2xxSuccessful()) {
//            return;
//        }
//        final List<ItemDTO> items = ids.getBody();
//        if (items==null) {
//            return;
//        }
//        // 3.转为 id 到 item的map
//        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
//        // 4.写入vo
//        for (CartVO v : vos) {
//            ItemDTO item = itemMap.get(v.getItemId());
//            if (item == null) {
//                continue;
//            }
//            v.setNewPrice(item.getPrice());
//            v.setStatus(item.getStatus());
//            v.setStock(item.getStock());
//        }
//
//
//    }
    //普通的远程调用
//
//    private void handleCartItems(List<CartVO> vos) {
//        // 1.获取商品id
//        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
//        //利用RestTemple获得ItemDTO
//        final ResponseEntity<List<ItemDTO>> ids = restTemplate.exchange(
//                "http://localhost:8081/items?ids={ids}",
//                HttpMethod.GET,
//                null,
//                new ParameterizedTypeReference<>() {
//                },
//                Map.of("ids", CollUtil.join(itemIds, ","))
//        );
//
//        if (!ids.getStatusCode().is2xxSuccessful()) {
//            return;
//        }
//        final List<ItemDTO> items = ids.getBody();
//        if (items==null) {
//            return;
//        }
//        // 3.转为 id 到 item的map
//        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
//        // 4.写入vo
//        for (CartVO v : vos) {
//            ItemDTO item = itemMap.get(v.getItemId());
//            if (item == null) {
//                continue;
//            }
//            v.setNewPrice(item.getPrice());
//            v.setStatus(item.getStatus());
//            v.setStock(item.getStock());
//        }
//    }

    @Override
    public void removeByItemIds(Collection<Long> itemIds) {
        // 1.构建删除条件，userId和itemId
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, UserContext.getUser())
                .in(Cart::getItemId, itemIds);
        // 2.删除
        remove(queryWrapper);
    }

    private void checkCartsFull(Long userId) {
        int count = lambdaQuery().eq(Cart::getUserId, userId).count();
        if (count >= 10) {
            throw new BizIllegalException(StrUtil.format("用户购物车课程不能超过{}", 10));
        }
    }

    private boolean checkItemExists(Long itemId, Long userId) {
        int count = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, itemId)
                .count();
        return count > 0;
    }
}
