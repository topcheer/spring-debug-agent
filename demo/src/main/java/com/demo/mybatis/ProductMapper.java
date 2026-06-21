package com.demo.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis Mapper for Product table.
 * Used by MyBatisInspector demo — shows mapped statements, type handlers, and cache.
 */
@Mapper
public interface ProductMapper {

    @Select("SELECT * FROM mybatis_product WHERE id = #{id}")
    Product findById(@Param("id") Long id);

    @Select("SELECT * FROM mybatis_product WHERE category = #{category}")
    List<Product> findByCategory(@Param("category") String category);

    @Select("SELECT COUNT(*) FROM mybatis_product")
    int count();

    // XML-defined statement (see mapper/ProductMapper.xml)
    int insertProduct(Product product);

    // XML-defined statement with dynamic SQL
    List<Product> searchProducts(@Param("name") String name, @Param("minPrice") Double minPrice,
                                  @Param("maxPrice") Double maxPrice);
}
