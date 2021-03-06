package com.cgcg.base.encrypt;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * 加密开关
 * .
 *
 * @author zhicong.lin
 * @date 2019/7/1
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({RequestBodyHandler.class, ResponseBodyHandler.class})
public @interface EnableEncryptApi {
}
