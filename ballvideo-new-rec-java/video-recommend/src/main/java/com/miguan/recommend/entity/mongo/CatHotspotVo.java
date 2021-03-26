package com.miguan.recommend.entity.mongo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * @author zhongli
 * @date 2020-08-11 
 *
 */
@Setter
@Getter
@Document("cat_hotspot")
@AllArgsConstructor
public class CatHotspotVo {
    private int parent_catid;
    private int catid;
    /**
     * 权重
     */
    private double weights;
}
