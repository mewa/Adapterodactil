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
import io.mewa.adapterodactil.annotations.Item;
import io.mewa.adapterodactil.annotations.Label;
import io.mewa.adapterodactil.annotations.OverridePlugin;
import io.mewa.adapterodactil.annotations.Row;
import io.mewa.adapterodactil.annotations.ViewType;
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

    private final static String METHOD_ONCREATE_VIEWHOLDER = "onCreateViewHolder";

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

    private boolean hasImpl(Element e, String method) {
        for (Element element : e.getEnclosedElements()) {
            if (element.getSimpleName().toString().equals(method))
                return !element.getModifiers().contains(Modifier.ABSTRACT);
        }
        return false;
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
        parsingInfo.element = (TypeElement) elem;

        parsingInfo.abstractCreateViewHolder = !hasImpl(elem, METHOD_ONCREATE_VIEWHOLDER);

        for (Element member : elem.getEnclosedElements()) {
            if (member.getAnnotation(Data.class) != null)
                parseData((ExecutableElement) member);

            if (member.getAnnotation(ViewType.class) != null) {
                parseViewType((TypeElement) member);
            }
            if (member.getAnnotation(Item.class) != null)
                parseItem((ExecutableElement) member);
        }

        TypeSpec adapter = createAdapter(elem);

        emit(parsingInfo.pkg, adapter);
    }

    private void parseViewType(TypeElement elem) {
        ViewType viewType = elem.getAnnotation(ViewType.class);

        messager.printMessage(Diagnostic.Kind.OTHER, "Parsing viewType: " + viewType.value());
        for (Element member : elem.getEnclosedElements()) {
            if (member.getAnnotation(Row.class) != null)
                parseRow(elem, (ExecutableElement) member, viewType.value());
        }
    }

    private TypeSpec createAdapter(Element elem) {
        final String viewHolderName = "ViewHolderImpl";

        TypeSpec.Builder adapter = TypeSpec.classBuilder(parsingInfo.adapterName)
                .addModifiers(Modifier.PUBLIC);

        createViewHolders(adapter, viewHolderName);

        parsingInfo.vhClassName = ClassName.get(parsingInfo.pkg.getQualifiedName().toString(), parsingInfo.adapterName + "." + viewHolderName);

        TypeElement superclass = (TypeElement) elem;

        implementAdapter(adapter);

        /* Extend adapter using ViewHolder's name */
        adapter.superclass(ParameterizedTypeName.get(ClassName.get(superclass),
                parsingInfo.vhClassName
        ));
        return adapter.build();
    }

    private void implementAdapter(TypeSpec.Builder adapter) {
        implementDataLogic(adapter);

        MethodSpec.Builder onCreateViewHolder = onCreateViewHolderImpl(adapter);
        MethodSpec.Builder onBindViewHolder = onBindViewHolderImpl(adapter);


        // if there are more than 1 view types user has to supply the relevant function
        if (!hasImpl(parsingInfo.element, "getItemViewType") && parsingInfo.adapterInfo.size() <= 1) {
            MethodSpec.Builder getItemViewType = MethodSpec.methodBuilder("getItemViewType")
                    .addParameter(TypeName.INT, "position")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.INT)
                    .addStatement("return $L", parsingInfo.adapterInfo.values().iterator().next().viewType);
            adapter.addMethod(getItemViewType.build());
        }

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

        MethodSpec.Builder dataGetter = MethodSpec.methodBuilder("getStored_" + dataInfo.field)
                .addModifiers(Modifier.PROTECTED)
                .returns(ClassName.get(inputData.asType()))
                .addStatement("return $L", dataInfo.field);

        MethodSpec.Builder itemCount = MethodSpec.methodBuilder("getItemCount")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.INT)
                .addStatement("return $L.size()", dataInfo.field);

        adapter.addMethod(dataSetter.build());
        adapter.addMethod(dataGetter.build());
        adapter.addMethod(itemCount.build());
    }

    private MethodSpec.Builder onBindViewHolderImpl(TypeSpec.Builder adapter) {
        final String argViewHolder = "vh";
        final String argPosition = "position";

        final String varData = "data";

        MethodSpec.Builder baseOnBindViewHolder = MethodSpec.methodBuilder("onBindViewHolder")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(parsingInfo.vhClassName, argViewHolder)
                .addParameter(TypeName.INT, argPosition);

        for (ViewTypeInfo viewTypeInfo : parsingInfo.adapterInfo.values()) {
            Integer viewType = viewTypeInfo.viewType;

            MethodSpec.Builder onBindViewHolder = MethodSpec.methodBuilder("onBindViewHolder" + viewType)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(parsingInfo.vhClassName.peerClass(parsingInfo.vhClassName.simpleName() + viewType), argViewHolder)
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

            for (int i = 0; i < viewTypeInfo.rows.size(); ++i) {
                RowInfo info = viewTypeInfo.rows.get(i);
                String iRowValue = "rowValue" + i;
                onBindViewHolder.addCode("\n");
                onBindViewHolder.addComment("$L $L generated using $L", Row.class.getSimpleName(), i, info.pluginInfo.plugin.getClass().getSimpleName());
                onBindViewHolder.addJavadoc("$L generated using {@link $L}<br/>\n", info.fields.data, info.pluginInfo.plugin.getClass().getCanonicalName());

                onBindViewHolder.addStatement("$T $L = $T.$L($L.$L, $L)", info.method.resultType, iRowValue, viewTypeInfo.viewTypeAdapter.asType(), info.method.methodName, argViewHolder, info.fields.data, varData);

                if (!info.pluginInfo.pluginName.equals(IgnorePlugin.class.getCanonicalName())) {
                    CodeBlock statement = CodeBlock.of("$L", info.pluginInfo.plugin.process(i, String.format("%s.%s", argViewHolder, info.fields.data), iRowValue));
                    onBindViewHolder.addCode(statement);
                }
            }

            // Item-wide properties handling
            if (parsingInfo.itemInfo != null) {
                onBindViewHolder.addCode("\n");
                onBindViewHolder.addStatement("$L($L.$L, $L, $L)", parsingInfo.itemInfo.method, argViewHolder, parsingInfo.vhRoot, argPosition, varData);
            }

            MethodSpec method = onBindViewHolder.build();
            adapter.addMethod(method);

            ClassName vhClass = parsingInfo.vhClassName.peerClass(parsingInfo.vhClassName.simpleName() + viewType);

            baseOnBindViewHolder
                    .beginControlFlow("if ($L.$L == $L)", argViewHolder, "viewType", viewType)
                    .addStatement("$L(($T) $L, $L)", method.name, vhClass, argViewHolder, argPosition)
                    .addStatement("return")
                    .endControlFlow();
        }
        return baseOnBindViewHolder;
    }

    private MethodSpec.Builder onCreateViewHolderImpl(TypeSpec.Builder adapter) {
        final String argContainer = "container";
        final String argViewType = "viewType";

        final String varInflater = "inflater";
        final String varContainer = "layout";
        final String varContainerViewGroup = "layoutVG";

        MethodSpec.Builder onCreateViewHolder = MethodSpec.methodBuilder(METHOD_ONCREATE_VIEWHOLDER)
                .addModifiers(Modifier.PUBLIC)
                .returns(parsingInfo.vhClassName)
                .addAnnotation(Override.class)
                .addParameter(VIEW_GROUP, argContainer)
                .addParameter(TypeName.INT, argViewType);

        for (ViewTypeInfo viewTypeInfo : parsingInfo.adapterInfo.values()) {
            Integer viewType = viewTypeInfo.viewType;

            onCreateViewHolder.beginControlFlow("if ($L == $L)", argViewType, viewType);

            onCreateViewHolder.addStatement("$T $L = $L.from($L.getContext())", LAYOUT_INFLATER, varInflater, LAYOUT_INFLATER, argContainer);
            onCreateViewHolder.addStatement(
                    "$T $L = ($T) $L.inflate($L, $L, false)",
                    VIEW_GROUP, varContainer, VIEW_GROUP, varInflater, parsingInfo.adapt.layout(), argContainer);


            onCreateViewHolder.addStatement("$T $L = ($T) $L.findViewById($L)", VIEW_GROUP, varContainerViewGroup, VIEW_GROUP, varContainer, parsingInfo.adapt.viewGroup());

            String retStatement = "return new $T($L, $L";

            for (int i = 0; i < viewTypeInfo.rows.size(); i++) {
                final RowInfo info = viewTypeInfo.rows.get(i);
                final String iRow = "row" + i;
                if (info.row.layout() == Row.LAYOUT_NONE) {
                    onCreateViewHolder.addStatement("$T $L = $L", VIEW, iRow, varContainer);
                } else {
                    onCreateViewHolder.addStatement(
                            "$T $L = $L.inflate($L, $L, false)",
                            VIEW, iRow, varInflater, info.row.layout(), varContainerViewGroup)
                            .addStatement(
                                    "$L.addView($L)", varContainerViewGroup, iRow
                            );
                }
                retStatement += ", " + iRow;
            }
            retStatement += ")";

            final ClassName typedViewHolderClass = parsingInfo.vhClassName
                    .peerClass(parsingInfo.vhClassName.simpleName() + String.valueOf(viewTypeInfo.viewType));

            onCreateViewHolder.addStatement(retStatement, typedViewHolderClass, argViewType, varContainer);

            onCreateViewHolder.endControlFlow();
        }

        if (!parsingInfo.abstractCreateViewHolder) {
            onCreateViewHolder
                    .beginControlFlow("else")
                    .addStatement("return super.$L($L, $L)", METHOD_ONCREATE_VIEWHOLDER, argContainer, argViewType)
                    .endControlFlow();
        } else {
            onCreateViewHolder.addStatement("throw new $T($L)", IllegalArgumentException.class, "String.format(\"Unsupported viewType %d\", " + argViewType + ")");
        }
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
     * @param adapter
     * @param vhName
     * @return
     */
    private void createViewHolders(TypeSpec.Builder adapter, String vhName) {
        TypeSpec.Builder baseHolder = TypeSpec.classBuilder(vhName)
                .superclass(VIEW_HOLDER)
                .addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC, Modifier.STATIC);

        final String argViewType = "viewType";
        final String argContainer = "container";
        final String root = "root";
        final String viewType = "viewType";
        parsingInfo.vhRoot = root;

        baseHolder.addField(VIEW, root, Modifier.PUBLIC);
        baseHolder.addField(
                FieldSpec.builder(TypeName.INT, "viewType", Modifier.PUBLIC)
                        .addModifiers(Modifier.FINAL)
                        .build()
        );

        MethodSpec.Builder baseCtor = MethodSpec.constructorBuilder()
                .addParameter(TypeName.INT, argViewType)
                .addParameter(VIEW_GROUP, argContainer)
                .addStatement("super($L)", argContainer)
                .addStatement("this.$L = $L", viewType, argViewType)
                .addStatement("$L = $L", root, argContainer);

        baseHolder.addMethod(baseCtor.build());

        adapter.addType(baseHolder.build());

        final String base = parsingInfo.pkg.toString() + "." + parsingInfo.adapterName;

        for (ViewTypeInfo viewTypeInfo : parsingInfo.adapterInfo.values()) {

            TypeSpec.Builder holder = TypeSpec.classBuilder(vhName + viewTypeInfo.viewType)
                    .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                    .superclass(ClassName.get(base, baseHolder.build().name));

            MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                    .addParameter(TypeName.INT, argViewType)
                    .addParameter(VIEW_GROUP, argContainer)
                    .addStatement("super($L, $L)", argViewType, argContainer);

            for (int i = 0; i < viewTypeInfo.rows.size(); ++i) {
                RowInfo info = viewTypeInfo.rows.get(i);

                final String iView = "view" + i;
                final String iLabel = "label" + i;
                final String iData = "data" + i;

                info.fields = new RowInfo.Fields(iLabel, iData);

                TypeName paramType = TypeName.get(info.method.paramType);

                ctor.addParameter(VIEW, iView);
                holder.addField(paramType, iData);

                String labelValue = info.label != null ? info.label.value() : "*none*";
                ctor.addComment(String.format(Locale.US, "%s %d, label: %s", Row.class.getSimpleName(), info.row.num(), labelValue));
                if (info.label != null) {
                    holder.addField(TEXT_VIEW, iLabel);
                    ctor.addCode(
                            CodeBlock.builder()
                                    .addStatement("$L = ($T) $L.findViewById($L)", iLabel, TEXT_VIEW, iView, info.label.id())
                                    .beginControlFlow("if ($L != null)", iLabel)
                                    .addStatement("$L.setText($S)", iLabel, info.label.value())
                                    .endControlFlow()
                                    .build()
                    );
                }
                ctor.addStatement("$L = ($T) $L.findViewById($L)", iData, paramType, iView, info.row.dataId());
            }
            holder.addMethod(ctor.build());
            adapter.addType(holder.build());
        }
    }

    private void parseRow(TypeElement viewTypeAdapter, ExecutableElement elem, int viewType) {
        Row row = elem.getAnnotation(Row.class);
        Label label = elem.getAnnotation(Label.class);
        OverridePlugin overridePlugin = elem.getAnnotation(OverridePlugin.class);
        final String method = elem.getSimpleName().toString();

        if (!elem.getModifiers().contains(Modifier.STATIC))
            throw new IllegalArgumentException("@Row annotated method must be static");

        String typeName = elem.getParameters().get(0).asType().toString();

        PluginInfo pluginInfo;
        if (overridePlugin == null)
            pluginInfo = getPluginForWidget(typeName);
        else
            pluginInfo = new PluginInfo(IgnorePlugin.class.getCanonicalName(), new IgnorePlugin());

        MethodInfo methodInfo = new MethodInfo(elem.getReturnType(), elem.getParameters().get(0).asType(), method);

        ViewTypeInfo viewTypeInfo = parsingInfo.adapterInfo.get(viewType);
        if (viewTypeInfo == null) {
            viewTypeInfo = new ViewTypeInfo(viewType, viewTypeAdapter);
            parsingInfo.adapterInfo.put(viewType, viewTypeInfo);
        }
        viewTypeInfo.rows.put(row.num(), new RowInfo(row, label, overridePlugin, methodInfo, pluginInfo));
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

    private void parseItem(ExecutableElement elem) {
        if (elem.getParameters().size() != 3) {
            throw new IllegalArgumentException("Invalid @Item signature. Expecting 3 arguments (view, position, data)");
        }
        parsingInfo.itemInfo = new ItemInfo(elem, elem.getSimpleName().toString(), elem.getParameters().get(0).asType());
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
        private Map<Integer, ViewTypeInfo> adapterInfo;
        private Adapt adapt;
        private DataInfo dataInfo;
        private ClassName vhClassName;
        private ItemInfo itemInfo;
        private String vhRoot;
        private TypeElement element;
        private boolean abstractCreateViewHolder = true;

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

    private static class ItemInfo {
        final TypeMirror paramType;
        final ExecutableElement element;
        final String method;

        private ItemInfo(ExecutableElement elem, String method, TypeMirror paramType) {
            this.element = elem;
            this.method = method;
            this.paramType = paramType;
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

    private class ViewTypeInfo {
        public final int viewType;
        public final TypeElement viewTypeAdapter;
        public final Map<Integer, RowInfo> rows;

        private ViewTypeInfo(int viewType, TypeElement viewTypeAdapter) {
            this.viewType = viewType;
            this.viewTypeAdapter = viewTypeAdapter;
            this.rows = new HashMap<>();
        }
    }
}
