package com.inzent.apim.utilities.pojocodec.annotation;

import com.inzent.apim.utilities.pojocodec.codec.*;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,
         ElementType.FIELD})
public @interface PojoCodec {
	Class<? extends AbstractTypeCodec> typeCodec() default DefaultTypeCodec.class;

	Class<? extends AbstractFieldCodec> fieldCodec() default DefaultFieldCodec.class;

	NullPolicy nullPolicy() default NullPolicy.INHERIT;

	EmptyPolicy emptyPolicy() default EmptyPolicy.INHERIT;

	ZonePolicy zonePolicy() default ZonePolicy.INHERIT;
}
