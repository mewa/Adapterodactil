package io.mewa.adapterodactil.plugins;

import com.squareup.javapoet.CodeBlock;

/**
 * Created by mewa on 6/16/17.
 */

public class TextViewPlugin implements Plugin {
    public static final String TEXT_VIEW = "android.widget.TextView";

    @Override
    public String forElement() {
        return TEXT_VIEW;
    }

    public CodeBlock process(int num, String view, Object result) {
        return CodeBlock.builder()
                .addStatement("$L.setText($L)", view, result)
                .build();
    }

}
