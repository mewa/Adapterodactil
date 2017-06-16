package io.mewa.adapterodactil.plugins;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;

/**
 * Created by mewa on 6/16/17.
 */

public class TextViewPlugin implements Plugin {
    public static final String NAME = TextViewPlugin.class.getCanonicalName();
    public static final String CLASS_NAME = "android.widget.TextView";

    public CodeBlock process(Object view, Object result) {
        return CodeBlock.builder()
                .addStatement("int xaxa = 555")
                .build();
    }

}
