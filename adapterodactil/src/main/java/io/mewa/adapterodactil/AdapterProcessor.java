package io.mewa.adapterodactil;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import io.mewa.adapterodactil.annotations.Adapt;
import io.mewa.adapterodactil.annotations.Data;
import io.mewa.adapterodactil.annotations.Label;
import io.mewa.adapterodactil.annotations.OverridePlugin;
import io.mewa.adapterodactil.annotations.Row;
import io.mewa.adapterodactil.plugins.IgnorePlugin;
import io.mewa.adapterodactil.plugins.Plugin;
import io.mewa.adapterodactil.plugins.TextViewPlugin;

@AutoService(Processor.class)
public class AdapterProcessor extends AbstractProcessor {
    private final static ClassName VIEW = ClassName.get("android.view", "View");
    private final static ClassName VIEW_GROUP = ClassName.get("android.view", "ViewGroup");
    private final static ClassName TEXT_VIEW = ClassName.get("android.widget", "TextView");
    private final static ClassName RECYCLER_VIEW = ClassName.get("android.support.v7.widget", "RecyclerView");
    private final static ClassName ADAPTER = ClassName.get("android.support.v7.widget.RecyclerView", "Adapter");
    private final static ClassName VIEW_HOLDER = ClassName.get("android.support.v7.widget.RecyclerView", "ViewHolder");
    private final static ClassName LAYOUT_INFLATER = ClassName.get("android.view", "LayoutInflater");

    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private Types typeUtils;
    private ParsingInfo parsingInfo;
    private Map<String, Plugin> plugins;


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        messager = processingEnv.getMessager();
        filer = processingEnv.getFiler();
        elementUtils = processingEnv.getElementUtils();
        typeUtils = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        plugins = new HashMap<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(Adapt.class)) {
            if (e.getKind() != ElementKind.CLASS) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@Adapt must be used on a type");
            }
            processAdapt(e);
        }
        return true;
    }

    private void processAdapt(Element elem) {
        parsingInfo = new ParsingInfo(elem);
        parsingInfo.adapt = elem.getAnnotation(Adapt.class);

        for (Element member : elem.getEnclosedElements()) {
            if (member.getAnnotation(Row.class) != null)
                parseRow((ExecutableElement) member);
            if (member.getAnnotation(Data.class) != null)
                parseData((ExecutableElement) member);
        }

        TypeSpec adapter = createAdapter(elem);

        emit(parsingInfo.pkg, adapter);
    }

    private TypeSpec createAdapter(Element elem) {
        final String viewHolderName = "ViewHolderImpl";

        TypeSpec.Builder adapter = TypeSpec.classBuilder(parsingInfo.adapterName);

        TypeSpec viewHolder = createViewHolder(viewHolderName);

        adapter.addType(viewHolder);

        parsingInfo.vhClassName = ClassName.get(parsingInfo.pkg.getQualifiedName().toString(), parsingInfo.adapterName + "." + viewHolder.name);

        TypeElement superclass = (TypeElement) elem;

        implementAdapter(adapter, viewHolder);

        /* Extend adapter using ViewHolder's name */
        adapter.superclass(ParameterizedTypeName.get(ClassName.get(superclass),
                parsingInfo.vhClassName
        ));
        return adapter.build();
    }

    private void implementAdapter(TypeSpec.Builder adapter, TypeSpec viewHolder) {
        implementDataLogic(adapter);

        MethodSpec.Builder onCreateViewHolder = onCreateViewHolderImpl(adapter, viewHolder);
        MethodSpec.Builder onBindViewHolder = onBindViewHolderImpl(adapter, viewHolder);

        adapter
                .addMethod(onCreateViewHolder.build())
                .addMethod(onBindViewHolder.build());
    }

    private void implementDataLogic(TypeSpec.Builder adapter) {
        DataInfo dataInfo = parsingInfo.dataInfo;

        // lazy way to extract Class at compile-time
        // clazz is never null
        TypeMirror clazz = null;
        try {
            parsingInfo.adapt.type();
        } catch (MirroredTypeException e) {
            clazz = e.getTypeMirror();
        }

        final String varElements = "elements";

        VariableElement inputData = dataInfo.element.getParameters().get(0);
        dataInfo.field = "stored_" + inputData.getSimpleName();

        FieldSpec.Builder storedData = FieldSpec.builder(ClassName.get(inputData.asType()), dataInfo.field, Modifier.PRIVATE)
                .initializer("new $T<>()", ArrayList.class);

        adapter.addField(storedData.build());

        MethodSpec.Builder dataSetter = MethodSpec.overriding(dataInfo.element)
                .addCode(
                        CodeBlock.builder()
                                .addStatement("$T<$T> $L", List.class, clazz, varElements)
                                .beginControlFlow("if ($L != null)", inputData)
                                .addStatement("$L = new $T<>($L)", varElements, ArrayList.class, inputData)
                                .endControlFlow()
                                .beginControlFlow("else")
                                .addStatement("$L = $T.emptyList()", varElements, Collections.class)
                                .endControlFlow()
                                .addStatement("this.$L = $L", dataInfo.field, varElements)
                                .build()
                );

        MethodSpec.Builder itemCount = MethodSpec.methodBuilder("getItemCount")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.INT)
                .addStatement("return $L.size()", dataInfo.field);

        adapter.addMethod(dataSetter.build());
        adapter.addMethod(itemCount.build());
    }

    private MethodSpec.Builder onBindViewHolderImpl(TypeSpec.Builder adapter, TypeSpec viewHolder) {
        final String argViewHolder = "vh";
        final String argPosition = "position";

        final String varData = "data";

        MethodSpec.Builder onBindViewHolder = MethodSpec.methodBuilder("onBindViewHolder")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(parsingInfo.vhClassName, argViewHolder)
                .addParameter(TypeName.INT, argPosition);

        // lazy way to extract Class at compile-time
        // clazz is never null
        TypeMirror clazz = null;
        try {
            parsingInfo.adapt.type();
        } catch (MirroredTypeException e) {
            clazz = e.getTypeMirror();
        }

        onBindViewHolder.addStatement("$T $L = $L.get($L)", clazz, varData, parsingInfo.dataInfo.field, argPosition);

        for (int i = 0; i < parsingInfo.adapterInfo.size(); ++i) {
            RowInfo info = parsingInfo.adapterInfo.get(i);
            String iRowValue = "rowValue" + i;
            onBindViewHolder.addCode("\n");
            onBindViewHolder.addComment("$L $L generated using $L", Row.class.getSimpleName(), i, info.pluginInfo.plugin.getClass().getSimpleName());
            onBindViewHolder.addJavadoc("$L generated using {@link $L}<br/>\n", info.fields.data, info.pluginInfo.plugin.getClass().getCanonicalName());

            onBindViewHolder.addStatement("$T $L = $L($L.$L, $L)", info.method.resultType, iRowValue, info.method.methodName, argViewHolder, info.fields.data, varData);

            if (!info.pluginInfo.pluginName.equals(IgnorePlugin.class.getCanonicalName())) {
                CodeBlock statement = CodeBlock.of("$L", info.pluginInfo.plugin.process(i, String.format("%s.%s", argViewHolder, info.fields.data), iRowValue));
                onBindViewHolder.addCode(statement);
            }
        }

        return onBindViewHolder;
    }

    private MethodSpec.Builder onCreateViewHolderImpl(TypeSpec.Builder adapter, TypeSpec viewHolder) {
        final String argContainer = "container";
        final String argViewType = "viewType";

        final String varInflater = "inflater";
        final String varContainer = "layout";
        final String varContainerViewGroup = "layoutVG";

        MethodSpec.Builder onCreateViewHolder = MethodSpec.methodBuilder("onCreateViewHolder")
                .addModifiers(Modifier.PUBLIC)
                .returns(parsingInfo.vhClassName)
                .addAnnotation(Override.class)
                .addParameter(VIEW_GROUP, argContainer)
                .addParameter(TypeName.INT, argViewType);

        onCreateViewHolder.addStatement("$T $L = $L.from($L.getContext())", LAYOUT_INFLATER, varInflater, LAYOUT_INFLATER, argContainer);
        onCreateViewHolder.addStatement(
                "$T $L = ($T) $L.inflate($L, $L, false)",
                VIEW_GROUP, varContainer, VIEW_GROUP, varInflater, parsingInfo.adapt.layout(), argContainer);


        onCreateViewHolder.addStatement("$T $L = ($T) $L.findViewById($L)", VIEW_GROUP, varContainerViewGroup, VIEW_GROUP, varContainer, parsingInfo.adapt.viewGroup());

        String retStatement = "return new $T($L";

        for (int i = 0; i < parsingInfo.adapterInfo.size(); i++) {
            final RowInfo info = parsingInfo.adapterInfo.get(i);
            final String iRow = "row" + i;
            onCreateViewHolder.addStatement(
                    "$T $L = $L.inflate($L, $L, false)",
                    VIEW, iRow, varInflater, info.row.layout(), varContainerViewGroup)
                    .addStatement(
                            "$L.addView($L)", varContainerViewGroup, iRow
                    );
            retStatement += ", " + iRow;
        }
        retStatement += ")";

        onCreateViewHolder.addStatement(retStatement, parsingInfo.vhClassName, varContainer);

        return onCreateViewHolder;
    }

    private void emit(PackageElement pkg, TypeSpec adapter) {
        try {
            JavaFile.builder(pkg.getQualifiedName().toString(), adapter)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, e.toString());
            e.printStackTrace();
        }
    }

    /**
     * Creates ViewHolder TypeSpec
     *
     * @param vhName
     * @return
     */
    private TypeSpec createViewHolder(String vhName) {
        TypeSpec.Builder holder = TypeSpec.classBuilder(vhName)
                .superclass(VIEW_HOLDER);

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addParameter(VIEW_GROUP, "container")
                .addStatement("super(container)");

        for (int i = 0; i < parsingInfo.adapterInfo.size(); ++i) {
            RowInfo info = parsingInfo.adapterInfo.get(i);
            final String iView = "view" + i;
            final String iLabel = "label" + i;
            final String iData = "data" + i;

            info.fields = new RowInfo.Fields(iLabel, iData);

            TypeName paramType = TypeName.get(info.method.paramType);

            ctor.addParameter(VIEW, iView);
            holder.addField(TEXT_VIEW, iLabel);
            holder.addField(paramType, iData);
            ctor.addComment(String.format(Locale.US, "%s %d, label: \"%s\"", Row.class.getSimpleName(), info.row.num(), info.label.value()));
            ctor.addCode(
                    CodeBlock.builder()
                            .addStatement("$L = ($T) $L.findViewById($L)", iLabel, TEXT_VIEW, iView, info.label.id())
                            .beginControlFlow("if ($L != null)", iLabel)
                            .addStatement("$L.setText($S)", iLabel, info.label.value())
                            .endControlFlow()
                            .build()
            );
            ctor.addStatement("$L = ($T) $L.findViewById($L)", iData, paramType, iView, info.row.dataId());
        }
        holder.addMethod(ctor.build());
        return holder.build();
    }

    private void parseRow(ExecutableElement elem) {
        Row row = elem.getAnnotation(Row.class);
        Label label = elem.getAnnotation(Label.class);
        OverridePlugin overridePlugin = elem.getAnnotation(OverridePlugin.class);
        final String method = elem.getSimpleName().toString();

        String typeName = elem.getParameters().get(0).asType().toString();

        PluginInfo pluginInfo;
        if (overridePlugin == null)
            pluginInfo = getPluginForWidget(typeName);
        else
            pluginInfo = new PluginInfo(IgnorePlugin.class.getCanonicalName(), new IgnorePlugin());

        MethodInfo methodInfo = new MethodInfo(elem.getReturnType(), elem.getParameters().get(0).asType(), method);

        parsingInfo.adapterInfo.put(row.num(), new RowInfo(row, label, overridePlugin, methodInfo, pluginInfo));
    }

    // TODO: this may prove useful once a plugin system gets implemented
    private PluginInfo getPluginForWidget(String clazz) {
        Plugin plugin = getPlugin(clazz);

        // no plugins available - use built-in TextView plugin
        if (plugin == null && clazz.equals(TextViewPlugin.TEXT_VIEW)) {
            plugin = new TextViewPlugin();
        }
        if (plugin == null) {
            throw new IllegalArgumentException(String.format("No plugin has been registered for handling %s", clazz));
        }
        messager.printMessage(Diagnostic.Kind.OTHER, "Using " + plugin + " for " + clazz);
        return new PluginInfo(plugin.getClass().getSimpleName(), plugin);
    }

    private Plugin getPluginNamed(String pluginName) {
        Plugin plugin = plugins.get(pluginName);
        if (plugin == null) {
            throw new IllegalArgumentException(String.format("Plugin %s has not been registered", pluginName));
        }
        return plugin;
    }

    /**
     * Returns first plugin registered for handling {@code clazz}
     *
     * @param clazz class of Android widget the {@code Plugin} should handle
     * @return {@code Plugin} instance if appropriate {@code Plugin} has been registered or null
     */
    private Plugin getPlugin(String clazz) {
        for (Map.Entry<String, Plugin> pluginEntry : plugins.entrySet()) {
            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "reading " + pluginEntry.getValue().forElement());
            if (clazz.equals(pluginEntry.getValue().forElement()))
                return pluginEntry.getValue();
        }
        return null;
    }

    private void parseData(ExecutableElement elem) {
        parsingInfo.dataInfo = new DataInfo(elem, elem.getAnnotation(Data.class));
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        annotations.add(OverridePlugin.class.getCanonicalName());
        annotations.add(Adapt.class.getCanonicalName());
        annotations.add(Row.class.getCanonicalName());
        annotations.add(Label.class.getCanonicalName());
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    private class ParsingInfo {
        private String adapterName;
        private PackageElement pkg;
        private Map<Integer, RowInfo> adapterInfo;
        private Adapt adapt;
        private DataInfo dataInfo;
        private ClassName vhClassName;

        private ParsingInfo(Element elem) {
            pkg = elementUtils.getPackageOf(elem);
            adapterName = elem.getSimpleName() + "Impl";
            adapterInfo = new HashMap<>();
        }
    }

    private static class DataInfo {
        final Data data;
        final ExecutableElement element;
        public String field;

        private DataInfo(ExecutableElement elem, Data data) {
            this.element = elem;
            this.data = data;
        }
    }

    private static class PluginInfo {
        final String pluginName;
        final Plugin plugin;

        private PluginInfo(String pluginName, Plugin plugin) {
            this.pluginName = pluginName;
            this.plugin = plugin;
        }
    }

    private static class MethodInfo {
        final TypeMirror resultType;
        final String methodName;
        final TypeMirror paramType;

        private MethodInfo(TypeMirror resultType, TypeMirror paramType, String methodName) {
            this.resultType = resultType;
            this.paramType = paramType;
            this.methodName = methodName;
        }
    }

    private static class RowInfo {
        final MethodInfo method;
        final Row row;
        final Label label;
        final PluginInfo pluginInfo;
        final OverridePlugin overridePlugin;

        Fields fields;

        RowInfo(Row row, Label label, OverridePlugin overridePlugin, MethodInfo method, PluginInfo pluginInfo) {
            this.row = row;
            this.label = label;
            this.overridePlugin = overridePlugin;
            this.method = method;
            this.pluginInfo = pluginInfo;
        }

        private static class Fields {
            /**
             * Holds viewholder's "data" field name
             */
            String data;
            /**
             * Holds viewholder's "label" field name
             */
            String label;

            Fields(String label, String data) {
                this.label = label;
                this.data = data;
            }
        }
    }
}
