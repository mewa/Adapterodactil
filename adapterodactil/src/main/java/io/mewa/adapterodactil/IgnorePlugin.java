package io.mewa.adapterodactil;

import com.squareup.javapoet.CodeBlock;

import io.mewa.adapterodactil.plugins.Plugin;

/**
 * Created by mewa on 6/19/17.
 */

class IgnorePlugin implements Plugin {
    @Override
    public String forElement() {
        throw new IllegalArgumentException("stub");
    }

    @Override
    public CodeBlock process(int num, String view, Object result) {
        throw new IllegalArgumentException("stub");
    }
}
