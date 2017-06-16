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
import java.lang.reflect.Method;
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
import io.mewa.adapterodactil.annotations.Row;
import io.mewa.adapterodactil.annotations.UsePlugin;
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
    private Map<String, String> plugins;


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
        for (Element e : roundEnv.getElementsAnnotatedWith(UsePlugin.class)) {
            if (e.getKind() != ElementKind.CLASS) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@UsePlugin must be used on a type");
            }
            processPlugin(e);
        }
        for (Element e : roundEnv.getElementsAnnotatedWith(Adapt.class)) {
            if (e.getKind() != ElementKind.CLASS) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@Adapt must be used on a type");
            }
            processAdapt(e);
        }
        return true;
    }

    private void processPlugin(Element e) {
        UsePlugin plugin = e.getAnnotation(UsePlugin.class);
        messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "plugin: " + plugin);
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
            String varResult = "result" + i;
            onBindViewHolder.addJavadoc("$L generated using {@link $L}<br/>\n", info.fields.data, info.pluginInfo.pluginName);
            onBindViewHolder.addStatement("$L($L.$L, $L)", info.method, argViewHolder, info.fields.data, varData);

            CodeBlock statement = info.pluginInfo.plugin.process(i, info.fields.data, varResult);
            onBindViewHolder.addCode(statement);

            onBindViewHolder.addStatement("$L.$L.setText($L($L.$L, $L))",
                    argViewHolder, info.fields.data, info.method, argViewHolder, info.fields.data, varData);
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

            ctor.addParameter(VIEW, iView);
            holder.addField(TEXT_VIEW, iLabel);
            holder.addField(TEXT_VIEW, iData);
            ctor.addComment(String.format(Locale.US, "Row %d, label: \"%s\"", info.row.num(), info.label.value()));
            ctor.addCode(
                    CodeBlock.builder()
                            .addStatement("$L = ($T) $L.findViewById($L)", iLabel, TEXT_VIEW, iView, info.label.id())
                            .beginControlFlow("if ($L != null)", iLabel)
                            .addStatement("$L.setText($S)", iLabel, info.label.value())
                            .endControlFlow()
                            .build()
            );
            ctor.addStatement("$L = ($T) $L.findViewById($L)", iData, TEXT_VIEW, iView, info.row.dataId());
        }
        holder.addMethod(ctor.build());
        return holder.build();
    }

    private void parseRow(ExecutableElement elem) {
        Row row = elem.getAnnotation(Row.class);
        Label label = elem.getAnnotation(Label.class);
        final String method = elem.getSimpleName().toString();

        String typeName = elem.getParameters().get(0).asType().toString();
        PluginInfo pluginInfo = getPlugin(typeName);

        parsingInfo.adapterInfo.put(row.num(), new RowInfo(row, label, method, pluginInfo));
    }

    private PluginInfo getPlugin(String clazz) {
        String plugin = plugins.get(clazz);

        // no plugins availables - use built-in TextView plugin
        if (plugin == null && clazz.equals(TextViewPlugin.CLASS_NAME)) {
            plugin = TextViewPlugin.NAME;
        }
        messager.printMessage(Diagnostic.Kind.OTHER, "Using " + plugin + " for " + clazz);
        try {
            Plugin pluginObject = (Plugin) Class.forName(plugin).newInstance();
            return new PluginInfo(plugin, pluginObject);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            messager.printMessage(Diagnostic.Kind.ERROR, "Plugin error: " + e);
        }
        messager.printMessage(Diagnostic.Kind.ERROR, "Cannot find plugin for " + clazz);
        return null;
    }

    private void parseData(ExecutableElement elem) {
        parsingInfo.dataInfo = new DataInfo(elem, elem.getAnnotation(Data.class));
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        annotations.add(UsePlugin.class.getCanonicalName());
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

    private static class RowInfo {
        final String method;
        final Row row;
        final Label label;
        final PluginInfo pluginInfo;

        Fields fields;

        RowInfo(Row row, Label label, String method, PluginInfo pluginInfo) {
            this.row = row;
            this.label = label;
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
