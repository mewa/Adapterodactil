package io.mewa.adapterodactil.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Created by mewa on 6/23/17.
 */

@Target(ElementType.TYPE)
public @interface ViewType {
    int value();
}
