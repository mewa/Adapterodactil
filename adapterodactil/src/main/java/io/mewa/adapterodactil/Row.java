package io.mewa.adapterodactil;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Created by mewa on 03.06.2017.
 */

@Target(ElementType.METHOD)
public @interface Row {
    int num();
    int dataId();
    int layout();
}
