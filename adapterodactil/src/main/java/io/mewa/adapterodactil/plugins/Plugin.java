package io.mewa.adapterodactil.plugins;

import com.squareup.javapoet.CodeBlock;

/**
 * Created by mewa on 6/16/17.
 */

public interface Plugin {
    CodeBlock process(Object view, Object result);
}
