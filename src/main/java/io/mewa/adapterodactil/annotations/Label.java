package io.mewa.adapterodactil.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Created by mewa on 03.06.2017.
 */

@Target(ElementType.METHOD)
public @interface Label {
    String value();
    int id();
}
