# SliceViewer: A minimal app that interactively displays Slices

See android doc about [Slices](https://developer.android.com/guide/slices).

##Features

* minimal code
* simple UI
* responsive & interactive
* auto-completes authority names


##Usage

1. Specify Slice URI
  * enter slice authority in `Authority` text field (should be auto-completed).
  * enter slice path in `Path` text field.
  * final Slice URI will be constructed as `content://[authority]/[path]`.
2. Choose Slice display mode (large / small / shorcut)
3. The corresponding Slice (if any) will be displayed below the input fields
