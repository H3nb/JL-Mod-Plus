To configure the behavior of the `FlexBox` container, create a `FlexBoxConfig` block and supply it using the `config` parameter.

<br />

```kotlin
FlexBox(
    config = {
        direction(FlexDirection.Column)
        wrap(FlexWrap.Wrap)
        alignItems(FlexAlignItems.Center)
        alignContent(FlexAlignContent.SpaceAround)
        justifyContent(FlexJustifyContent.Center)
        gap(16.dp)
    }
) { // child items
}
   
```

<br />

Use `FlexBoxConfig` to define the layout direction, wrapping behavior, alignment, and gaps between items.

## Layout direction

The `direction` function sets the main axis, which dictates the direction items are laid out in. It accepts the following values:

- `Row` (default): Sets the main axis to be horizontal. In left-to-right locales this will be left-to-right, with the opposite in right-to-left.
- `RowReverse`: Reverses the direction of `Row`.
- `Column`: Sets the main axis to be vertical, top-to-bottom.
- `ColumnReverse`: Reverses the direction of `Column`.

## Align items and distribute extra space

The following sections describe how to align items and distribute extra space along the main and cross axes.

### Along the main axis

Use `justifyContent` to distribute items along the main axis. The following table shows the behavior when the direction is `Row`.

|---|---|
|   | ![Illustration of a horizontal main axis.](https://developer.android.com/static/develop/ui/compose/images/layouts/adaptive/flexbox/main-axis.png) |
| `Start` | ![Items aligned to the start of the main axis.](https://developer.android.com/static/develop/ui/compose/images/layouts/adaptive/flexbox/mainaxis-start.png) |
| `Center` | ![Items aligned to the center of the main axis.](https://developer.android.com/static/develop/ui/compose/images/layouts/adaptive/flexbox/mainaxis-center.png) |
| `End` | ![Items aligned to the end of the main axis.](https://developer.android.com/static/develop/ui/compose/images/layouts/adaptive/flexbox/mainaxis-end.png) |
| `SpaceBetween` | ![Items distributed along the main axis with space between them.](https://developer.android.com/static/develop/ui/compose/images/layouts/adaptive/flexbox/mainaxis-spacebetween.png) |
| `SpaceAround` | ![Items distributed along the main axm}€ﬁ∫∂âûÀk∫wµÁK‹ô]öY]Àÿ€€\‹ŸK\ÿ‹ôY[ú⁄›]\›[ôÀ\ô[X\ŸK[õ›\ K