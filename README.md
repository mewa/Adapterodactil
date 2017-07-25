# Adapterodactil

Adapterodactil is an annotation processor for generating `RecyclerView.Adapter`s aiming to reduce the amount of boilerplate code needed to create a working `Adapter`.

## Usage

To use Adapterodactil, simply annotate a class extending `RecyclerView.Adapter` with `@Adapt` annotation and fill in needed values.

```java
@Adapt(layout = containerLayout, viewGroup = containerViewGroup, type = ItemT.class)
public abstract class GeneratedAdapter<T extends RecyclerView.ViewHolder> 
	extends RecyclerView.Adapter<T> {}
```

Where `containerLayout` is an XML layout resource containing a `ViewGroup` with id `containerViewGroup` and `ItemT` is the type of the items that will be displayed in the adapter.

### View types

For every `@ViewType` annotated inner class a `ViewHolder` will be created. `@ViewType` annotation takes an optional `int` parameter which sets the view type of generated `View` and `ViewHolder`. 

Please note that if you are using multiple view types **you must provide an implementation of** `getItemViewType`. For a single view type an appropriate implementation will be generated. 

### Rows and plugins
Methods used to translate data to a displayable format must have a following signature:
```java
@Row(num = n, dataId = {ids})
ReturnT translate(ViewT view, @IdRes int id, ItemT item)
```
and be annotated with `@Row` annotations as well as be members of an inner class annotated with `@ViewType`.

When used, this method will pass the `View` found under `id` along with data `item` from the respective position in the adapter. `id` is a value from array of `ids` in the `@Row annotation`.

The returned value of type `ReturnT` will be passed to respective plugin, registered for `ViewT`, which makes use of that value. By default only `TextViewPlugin` is registered, which assumes a `ViewT` of `TextView` and `ReturnT` of `String` and sets the `TextView`'s text to the returned value. If you want to suppress this behaviour and use different `ViewT` you have to annotate the method with `@OverridePlugin` annotation, which will cause an `IgnorePlugin` to be applied and skip processing the return value (you can then set it to `void` too).

#### Row flavours
By default the container `ViewGroup` will be used to search for `ids` specified in the `@Row`. 

You can however specify the `layout` parameter, which will specify the data layout. This layout will be then inflated for every `id` in `ids` and the inflated view will be searched for `id`.
