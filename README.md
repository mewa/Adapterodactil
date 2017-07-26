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

### Data

If you want to use the base type it's a good idea to create an abstract data setter and annotate it with `@Data`. Regardless, both a setter and a will be generated, but if it's abstract you will be able to access it from the base type. If a `@Data` annotated setter method exists the getter's name will be inferred from its parameter name.

For instance, 
```java
@Data
public abstract setMyData(List<MyData> mostImportantData);
```
will generate an appropriate setter with the same name and a getter `getMostImportantData`.

If no `@Data`-annotated method exists, a setter of `setData` and getter of `getData` is used by default.

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

### Example

#### With embedded row layout

```java
@Adapt(layout = R.layout.container_layout, viewGroup = R.id.container, type = Item.class)
public abstract class TimelineAdapter<T extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<T> {

    @ViewType
    public static class ItemAdapter {

        @Row(num = 0, dataId = R.id.item_title)
        public String title(TextView view, @IdRes int id, Item item) {
			return item.getTitle();
        }

		@OverridePlugin
        @Row(num = 1, dataId = R.id.item_image)
        public void image(ImageView view, @IdRes int id, Item item) {
			view.setImageDrawable(item.getDrawable());
        }

        @Row(num = 2, dataId = { R.id.number1, R.id.number2 })
        public String content(TextView view, @IdRes int id, Item item) {
			switch (id) {
				case R.id.number1:
					return String.valueOf(item.getNumber1());
				case R.id.number2:
					return String.valueOf(item.getNumber2());
			}
			return null;
        }

    }
```

```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
	android:id="@+id/timeline_item_container"
	android:layout_width="match_parent"
	android:layout_height="wrap_content"
	android:orientation="vertical" >
	<TextView 
		android:id="@+id/item_title"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
	<ImageView 
		android:id="@+id/item_image"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
	<TextView 
		android:id="@+id/number1"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
	<TextView 
		android:id="@+id/number2"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
</LinearLayout>
```

#### With inflated row layout

```java
@Adapt(layout = R.layout.container_layout, viewGroup = R.id.container, type = Item.class)
public abstract class TimelineAdapter<T extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<T> {

    @ViewType
    public static class ItemAdapter {

        @Row(num = 0, dataId = R.id.item_title)
        public String title(TextView view, @IdRes int id, Item item) {
			return item.getTitle();
        }

		@OverridePlugin
        @Row(num = 1, dataId = R.id.item_image)
        public void image(ImageView view, @IdRes int id, Item item) {
			view.setImageDrawable(item.getDrawable());
        }

        @Row(num = 2, dataId = { R.id.number1, R.id.number2 })
        public String content(TextView view, @IdRes int id, Item item) {
			switch (id) {
				case R.id.number1:
					return String.valueOf(item.getNumber1());
				case R.id.number2:
					return String.valueOf(item.getNumber2());
			}
			return null;
        }

    }
```

```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
	android:id="@+id/timeline_item_container"
	android:layout_width="match_parent"
	android:layout_height="wrap_content"
	android:orientation="vertical" >
	<TextView 
		android:id="@+id/item_title"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
	<ImageView 
		android:id="@+id/item_image"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
	<TextView 
		android:id="@+id/number1"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
	<TextView 
		android:id="@+id/number2"
		android:layout_width="wrap_content"
		android:layout_height="wrap_content" />
</LinearLayout>
```
