package io.mewa.adapterodactil.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Created by mewa on 03.06.2017.
 */

@Target(ElementType.METHOD)
public @interface Row {
    int GENERATED_VIEW = 0xB4df00d;
    int LAYOUT_NONE = -1;

    int num();
    int dataId();
    int layout() default LAYOUT_NONE;
    int viewType() default GENERATED_VIEW;
}
