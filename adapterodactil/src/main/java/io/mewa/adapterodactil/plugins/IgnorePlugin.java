package io.mewa.adapterodactil.plugins;

import com.squareup.javapoet.CodeBlock;

/**
 * Created by mewa on 6/19/17.
 */

/**
 * Plugin overriding default TextViewPlugin's code generation, applied
 * by using #{@link io.mewa.adapterodactil.annotations.OverridePlugin} annotation
 */
public class IgnorePlugin implements Plugin {
    @Override
    public String forElement() {
        throw new IllegalArgumentException("stub");
    }

    @Override
    public CodeBlock process(int num, String view, Object result) {
        throw new IllegalArgumentException("stub");
    }
}
