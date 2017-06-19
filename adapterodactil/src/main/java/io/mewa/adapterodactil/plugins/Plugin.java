package io.mewa.adapterodactil.plugins;

import com.squareup.javapoet.CodeBlock;

/**
 * Created by mewa on 6/16/17.
 */

public interface Plugin {
    String forElement();

    CodeBlock process(int num, String view, Object result);
}
