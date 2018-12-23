package io.anuke.arc.scene.ui.layout;

import io.anuke.arc.collection.Array;
import io.anuke.arc.function.BooleanConsumer;
import io.anuke.arc.function.Consumer;
import io.anuke.arc.function.Supplier;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.scene.Element;
import io.anuke.arc.scene.event.Touchable;
import io.anuke.arc.scene.style.Drawable;
import io.anuke.arc.scene.ui.*;
import io.anuke.arc.scene.ui.Label.LabelStyle;
import io.anuke.arc.scene.ui.TextField.TextFieldFilter;
import io.anuke.arc.scene.utils.Elements;
import io.anuke.arc.util.Align;
import io.anuke.arc.util.pooling.Pool;

import static io.anuke.arc.Core.graphics;
import static io.anuke.arc.Core.scene;

/**
 * A group that sizes and positions children using table constraints. By default, {@link #getTouchable()} is
 * {@link Touchable#childrenOnly}.
 * <p>
 * The preferred and minimum sizes are that of the children when laid out in columns and rows.
 * @author Nathan Sweet
 */
public class Table extends WidgetGroup{
    static final Pool<Cell> cellPool = new Pool<Cell>(){
        protected Cell newObject(){
            return new Cell();
        }
    };
    public static Color debugTableColor = new Color(0, 0, 1, 1);
    public static Color debugCellColor = new Color(1, 0, 0, 1);
    public static Color debugActorColor = new Color(0, 1, 0, 1);
    /** Value that is the top padding of the table's background. */
    public static Value backgroundTop = context -> {
        Drawable background = ((Table)context).background;
        return background == null ? 0 : background.getTopHeight();
    };
    /** Value that is the left padding of the table's background. */
    public static Value backgroundLeft = context -> {
        Drawable background = ((Table)context).background;
        return background == null ? 0 : background.getLeftWidth();
    };
    /** Value that is the bottom padding of the table's background. */
    public static Value backgroundBottom = context -> {
        Drawable background = ((Table)context).background;
        return background == null ? 0 : background.getBottomHeight();
    };
    /** Value that is the right padding of the table's background. */
    public static Value backgroundRight = context -> {
        Drawable background = ((Table)context).background;
        return background == null ? 0 : background.getRightWidth();
    };
    static private float[] columnWeightedWidth, rowWeightedHeight;
    private final Array<Cell> cells = new Array<>(4);
    private final Cell cellDefaults;
    private final Array<Cell> columnDefaults = new Array<>(2);
    Value padTop = backgroundTop, padLeft = backgroundLeft, padBottom = backgroundBottom, padRight = backgroundRight;
    int align = Align.center;
    Drawable background;
    boolean round = true;
    private int columns, rows;
    private boolean implicitEndRow;
    private Cell rowDefaults;
    private boolean sizeInvalid = true;
    private float[] columnMinWidth, rowMinHeight;
    private float[] columnPrefWidth, rowPrefHeight;
    private float tableMinWidth, tableMinHeight;
    private float tablePrefWidth, tablePrefHeight;
    private float[] columnWidth, rowHeight;
    private float[] expandWidth, expandHeight;
    private boolean clip;

    /**
     * Creates a table with a skin, which enables the {@link #add(CharSequence)} and {@link #add(CharSequence, String)} methods to
     * be used.
     */
    public Table(){

        cellDefaults = obtainCell();

        setTransform(false);
        touchable(Touchable.childrenOnly);
    }

    public Table(String background){
        this();

        background(background);
    }

    private Cell obtainCell(){
        Cell cell = cellPool.obtain();
        cell.setLayout(this);
        return cell;
    }

    /** Adds and fills a new table within this one. */
    public Table fill(){
        Table table = new Table();
        table.setFillParent(true);
        add(table);
        return table;
    }

    @Override
    public void draw(){
        validate();
        if(isTransform()){
            applyTransform(computeTransform());
            drawBackground(0, 0);
            if(clip){
                graphics.batch().flush();
                float padLeft = this.padLeft.get(this), padBottom = this.padBottom.get(this);
                if(clipBegin(padLeft, padBottom, getWidth() - padLeft - padRight.get(this),
                getHeight() - padBottom - padTop.get(this))){
                    drawChildren();
                    graphics.batch().flush();
                    clipEnd();
                }
            }else
                drawChildren();
            resetTransform();
        }else{
            drawBackground(x, y);
            super.draw();
        }
    }

    /**
     * Called to draw the background, before clipping is applied (if enabled). Default implementation draws the background
     * drawable.
     */
    protected void drawBackground(float x, float y){
        if(background == null) return;
        Color color = getColor();
        graphics.batch().setColor(color.r, color.g, color.b, color.a * parentAlpha);
        background.draw(x, y, getWidth(), getHeight());
    }

    /** @see #setBackground(Drawable) */
    public Table background(Drawable background){
        setBackground(background);
        return this;
    }

    /** @see #setBackground(String) */
    public Table background(String drawableName){
        setBackground(drawableName);
        return this;
    }

    public Drawable getBackground(){
        return background;
    }

    /**
     * Sets the background drawable from the skin and adjusts the table's padding to match the background.
     * @see #setBackground(Drawable)
     */
    public void setBackground(String drawableName){
        if(scene.skin == null) throw new IllegalStateException("Table must have a skin set to use this method.");
        setBackground(scene.skin.getDrawable(drawableName));
    }

    /** @param background May be null to clear the background. */
    public void setBackground(Drawable background){
        if(this.background == background) return;
        float padTopOld = getMarginTop(), padLeftOld = getMarginLeft(), padBottomOld = getMarginBottom(), padRightOld = getMarginRight();
        this.background = background; // The default margin values use the background's padding.
        float padTopNew = getMarginTop(), padLeftNew = getMarginLeft(), padBottomNew = getMarginBottom(), padRightNew = getMarginRight();
        if(padTopOld + padBottomOld != padTopNew + padBottomNew || padLeftOld + padRightOld != padLeftNew + padRightNew)
            invalidateHierarchy();
        else if(padTopOld != padTopNew || padLeftOld != padLeftNew || padBottomOld != padBottomNew || padRightOld != padRightNew)
            invalidate();
    }

    public Element hit(float x, float y, boolean touchable){
        if(clip){
            if(touchable && getTouchable() == Touchable.disabled) return null;
            if(x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) return null;
        }
        return super.hit(x, y, touchable);
    }

    public boolean getClip(){
        return clip;
    }

    /**
     * Causes the contents to be clipped if they exceed the table element's bounds. Enabling clipping will set
     * {@link #setTransform(boolean)} to true.
     */
    public void setClip(boolean enabled){
        clip = enabled;
        setTransform(enabled);
        invalidate();
    }

    public void invalidate(){
        sizeInvalid = true;
        super.invalidate();
    }

    /** Adds a new cell to the table with the specified element. */
    public <T extends Element> Cell<T> add(T element){
        Cell<T> cell = obtainCell();
        cell.element = element;

        // The row was ended for layout, not by the user, so revert it.
        if(implicitEndRow){
            implicitEndRow = false;
            rows--;
            cells.peek().endRow = false;
        }

        Array<Cell> cells = this.cells;
        int cellCount = cells.size;
        if(cellCount > 0){
            // Set cell column and row.
            Cell lastCell = cells.peek();
            if(!lastCell.endRow){
                cell.column = lastCell.column + lastCell.colspan;
                cell.row = lastCell.row;
            }else{
                cell.column = 0;
                cell.row = lastCell.row + 1;
            }
            // Set the index of the cell above.
            if(cell.row > 0){
                outer:
                for(int i = cellCount - 1; i >= 0; i--){
                    Cell other = cells.get(i);
                    for(int column = other.column, nn = column + other.colspan; column < nn; column++){
                        if(column == cell.column){
                            cell.cellAboveIndex = i;
                            break outer;
                        }
                    }
                }
            }
        }else{
            cell.column = 0;
            cell.row = 0;
        }
        cells.add(cell);

        cell.set(cellDefaults);
        if(cell.column < columnDefaults.size){
            Cell columnCell = columnDefaults.get(cell.column);
            if(columnCell != null) cell.merge(columnCell);
        }
        cell.merge(rowDefaults);

        if(element != null) addChild(element);

        return cell;
    }

    public void add(Element... elements){
        for(Element element : elements) add(element);
    }

    public Cell<Table> table(){
        return table((String)null);
    }

    public Cell<Table> table(String background){
        Table table = new Table(background);
        return add(table);
    }

    public Cell<Table> table(Consumer<Table> cons){
        Table table = new Table();
        cons.accept(table);
        return add(table);
    }

    public Cell<Table> table(String background, Consumer<Table> cons){
        return table(background, Align.center, cons);
    }

    public Cell<Table> table(String background, int align, Consumer<Table> cons){
        Table table = new Table(background);
        table.align(align);
        cons.accept(table);
        return add(table);
    }

    public Cell<Label> label(Supplier<CharSequence> text){
        return add(new Label(text));
    }

    public Cell<Label> labelWrap(Supplier<CharSequence> text){
        Label label = new Label(text);
        label.setWrap(true);
        return add(label);
    }

    public Cell<Label> labelWrap(String text){
        Label label = new Label(text);
        label.setWrap(true);
        return add(label);
    }

    public Cell<ScrollPane> pane(String style, Element element){
        ScrollPane pane = new ScrollPane(element, style);
        return add(pane);
    }

    public Cell<ScrollPane> pane(Element element){
        return pane("default", element);
    }

    /** Adds a new cell with a label. */
    public Cell<Label> add(CharSequence text){
        return add(new Label(text));
    }

    /** Adds a new cell with a label. */
    public Cell<Label> add(CharSequence text, float scl){
        Label l = new Label(text);
        l.setFontScale(scl);
        return add(l);
    }

    /** Adds a new cell with a label. */
    public Cell<Label> add(CharSequence text, String labelStyleName, float scl){
        Label l = new Label(text, scene.skin.get(labelStyleName, LabelStyle.class));
        l.setFontScale(scl);
        return add(l);
    }

    public Cell<Label> add(CharSequence text, Color color, float scl){
        Label l = new Label(text);
        l.setColor(color);
        l.setFontScale(scl);
        return add(l);
    }

    /** Adds a new cell with a label. */
    public Cell<Label> add(CharSequence text, String labelStyleName){
        return add(new Label(text, scene.skin.get(labelStyleName, LabelStyle.class)));
    }

    /** Adds a new cell with a label. */
    public Cell<Label> add(CharSequence text, Color color){
        return add(new Label(text, new LabelStyle(scene.skin.getFont("default-font"), color)));
    }

    /** Adds a new cell with a label. */
    public Cell<Label> add(CharSequence text, String fontName, String colorName){
        return add(new Label(text, new LabelStyle(scene.skin.getFont(fontName), scene.skin.getColor(colorName))));
    }

    /** Adds a cell without an element. */
    public Cell add(){
        return add((Element)null);
    }

    /**
     * Adds a new cell to the table with the specified elements in a {@link Stack}.
     * @param elements May be null to add a stack without any elements.
     */
    public Cell<Stack> stack(Element... elements){
        Stack stack = new Stack();
        if(elements != null){
            for(int i = 0, n = elements.length; i < n; i++)
                stack.addChild(elements[i]);
        }
        return add(stack);
    }

    public Cell<Image> addImage(String name){
        return add(new Image(scene.skin.getPatch(name)));
    }

    public Cell<Image> addImage(String name, Color color){
        Image image = new Image(scene.skin.getPatch(name));
        image.setColor(color);
        return add(image);
    }

    public Cell<Image> addImage(TextureRegion region){
        return add(new Image(region));
    }

    public Cell<CheckBox> addCheck(String text, BooleanConsumer listener){
        CheckBox button = Elements.newCheck(text, listener);
        return add(button);
    }

    public Cell<CheckBox> addCheck(String text, boolean checked, BooleanConsumer listener){
        CheckBox button = Elements.newCheck(text, listener);
        button.setChecked(checked);
        return add(button);
    }

    public Cell<CheckBox> addCheck(String text, float imagesize, boolean checked, BooleanConsumer listener){
        CheckBox button = Elements.newCheck(text, listener);
        button.getImageCell().size(imagesize);
        button.setChecked(checked);
        return add(button);
    }

    public Cell<TextButton> addButton(String text, Runnable listener){
        TextButton button = Elements.newButton(text, listener);
        return add(button);
    }

    public Cell<TextButton> addButton(String text, String style, Runnable listener){
        TextButton button = Elements.newButton(text, style, listener);
        return add(button);
    }

    public Cell<ImageButton> addImageButton(String icon, Runnable listener){
        ImageButton button = Elements.newImageButton(icon, listener);
        return add(button);
    }

    public Cell<ImageButton> addImageButton(String icon, float isize, Runnable listener){
        ImageButton button = Elements.newImageButton(icon, listener);
        button.resizeImage(isize);
        return add(button);
    }

    public Cell<ImageButton> addImageButton(String icon, String style, float isize, Runnable listener){
        ImageButton button = new ImageButton(icon, style);
        button.clicked(listener);
        button.resizeImage(isize);
        return add(button);
    }

    public Cell<TextField> addField(String text, Consumer<String> listener){
        TextField field = Elements.newField(text, listener);
        return add(field);
    }

    public Cell<TextArea> addArea(String text, Consumer<String> listener){
        TextArea area = new TextArea(text);
        area.changed(() -> listener.accept(area.getText()));
        return add(area);
    }

    public Cell<TextArea> addArea(String text, String style, Consumer<String> listener){
        TextArea area = new TextArea(text, style);
        area.changed(() -> listener.accept(area.getText()));
        return add(area);
    }

    public Cell<TextField> addField(String text, TextFieldFilter filter, Consumer<String> listener){
        TextField field = Elements.newField(text, listener);
        field.setTextFieldFilter(filter);
        return add(field);
    }

    public Cell addRect(DrawRect draw){
        return add(new Element(){
            public void draw(){
                draw.draw(getX(), getY(), getWidth(), getHeight());
            }
        });
    }

    public Table addTable(String background){
        Table table = new Table();
        table.background(background);
        add(table);
        return table;
    }

    public Cell<TextButton> addRowImageTextButton(String text, String image, float imagesize, Runnable clicked){
        TextButton button = new TextButton(text);
        button.clearChildren();
        button.add(new Image(image)).size(imagesize).update(i -> i.setColor(button.isDisabled() ? Color.GRAY : Color.WHITE));
        button.row();
        button.add(button.getLabel()).padTop(4);
        button.clicked(clicked);
        return add(button);
    }


    public Cell<TextButton> addImageTextButton(String text, String image, float imagesize, Runnable clicked){
        TextButton button = new TextButton(text);
        button.add(new Image(image)).size(imagesize);
        button.getCells().reverse();
        button.clicked(clicked);
        return add(button);
    }

    public Cell<TextButton> addImageTextButton(String text, String image, String style, float imagesize, Runnable clicked){
        TextButton button = new TextButton(text, style);
        button.add(new Image(image)).size(imagesize);
        button.getCells().reverse();
        button.clicked(clicked);
        return add(button);
    }

    public Cell<TextButton> addCenteredImageTextButton(String text, String image, float imagesize, Runnable clicked){
        TextButton button = new TextButton(text);
        button.add(new Image(image)).size(imagesize);
        button.getCells().reverse();
        button.clicked(clicked);
        button.getLabelCell().padLeft(-imagesize);
        return add(button);
    }

    public Cell<TextButton> addCenteredImageTextButton(String text, String image, String style, float imagesize, Runnable clicked){
        TextButton button = new TextButton(text, style);
        button.add(new Image(image)).size(imagesize);
        button.getCells().reverse();
        button.clicked(clicked);
        button.getLabelCell().padLeft(-imagesize);
        return add(button);
    }

    public Cell<Slider> addSlider(float min, float max, float step, Consumer<Float> listener){
        return addSlider(min, max, step, 0f, listener);
    }

    public Cell<Slider> addSlider(float min, float max, float step, float defvalue, Consumer<Float> listener){
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(defvalue);
        if(listener != null)
            slider.moved(listener);
        return add(slider);
    }

    public Cell<Slider> addSlider(float min, float max, float step, float defvalue, boolean onUp, Consumer<Float> listener){
        Slider slider = new Slider(min, max, step, false);
        slider.setValue(defvalue);
        if(listener != null){
            if(!onUp){
                slider.moved(listener);
            }else{
                slider.released(() -> listener.accept(slider.getValue()));
            }

        }
        return add(slider);
    }

    public boolean removeChild(Element element){
        return removeChild(element, true);
    }

    public boolean removeChild(Element element, boolean unfocus){
        if(!super.removeChild(element, unfocus)) return false;
        Cell cell = getCell(element);
        if(cell != null) cell.element = null;
        return true;
    }

    /** Removes all actors and cells from the table. */
    public void clearChildren(){
        Array<Cell> cells = this.cells;
        for(int i = cells.size - 1; i >= 0; i--){
            Cell cell = cells.get(i);
            Element actor = cell.element;
            if(actor != null) actor.remove();
        }
        cellPool.freeAll(cells);
        cells.clear();
        rows = 0;
        columns = 0;
        if(rowDefaults != null) cellPool.free(rowDefaults);
        rowDefaults = null;
        implicitEndRow = false;

        super.clearChildren();
    }

    /**
     * Removes all actors and cells from the table (same as {@link #clearChildren()}) and additionally resets all table properties
     * and cell, column, and row defaults.
     */
    public void reset(){
        clearChildren();
        padTop = backgroundTop;
        padLeft = backgroundLeft;
        padBottom = backgroundBottom;
        padRight = backgroundRight;
        align = Align.center;
        cellDefaults.reset();
        for(int i = 0, n = columnDefaults.size; i < n; i++){
            Cell columnCell = columnDefaults.get(i);
            if(columnCell != null) cellPool.free(columnCell);
        }
        columnDefaults.clear();
    }

    /**
     * Indicates that subsequent cells should be added to a new row and returns the cell values that will be used as the defaults
     * for all cells in the new row.
     */
    public Cell row(){
        if(cells.size > 0){
            if(!implicitEndRow) endRow();
            invalidate();
        }
        implicitEndRow = false;
        if(rowDefaults != null) cellPool.free(rowDefaults);
        rowDefaults = obtainCell();
        rowDefaults.clear();
        return rowDefaults;
    }

    private void endRow(){
        Array<Cell> cells = this.cells;
        int rowColumns = 0;
        for(int i = cells.size - 1; i >= 0; i--){
            Cell cell = cells.get(i);
            if(cell.endRow) break;
            rowColumns += cell.colspan;
        }
        columns = Math.max(columns, rowColumns);
        rows++;
        cells.peek().endRow = true;
    }

    /**
     * Gets the cell values that will be used as the defaults for all cells in the specified column. Columns are indexed starting
     * at 0.
     */
    public Cell columnDefaults(int column){
        Cell cell = columnDefaults.size > column ? columnDefaults.get(column) : null;
        if(cell == null){
            cell = obtainCell();
            cell.clear();
            if(column >= columnDefaults.size){
                for(int i = columnDefaults.size; i < column; i++)
                    columnDefaults.add(null);
                columnDefaults.add(cell);
            }else
                columnDefaults.set(column, cell);
        }
        return cell;
    }

    /** Returns the cell for the specified actor in this table, or null. */
    public <T extends Element> Cell getCell(T actor){
        Array<Cell> cells = this.cells;
        for(int i = 0, n = cells.size; i < n; i++){
            Cell c = cells.get(i);
            if(c.element == actor) return c;
        }
        return null;
    }

    /** Returns the cells for this table. */
    public Array<Cell> getCells(){
        return cells;
    }

    public float getPrefWidth(){
        if(sizeInvalid) computeSize();
        float width = tablePrefWidth;
        if(background != null) return Math.max(width, background.getMinWidth());
        return width;
    }

    public float getPrefHeight(){
        if(sizeInvalid) computeSize();
        float height = tablePrefHeight;
        if(background != null) return Math.max(height, background.getMinHeight());
        return height;
    }

    public float getMinWidth(){
        if(sizeInvalid) computeSize();
        return tableMinWidth;
    }

    public float getMinHeight(){
        if(sizeInvalid) computeSize();
        return tableMinHeight;
    }

    /** The cell values that will be used as the defaults for all cells. */
    public Cell defaults(){
        return cellDefaults;
    }

    /** Sets the marginTop, marginLeft, marginBottom, and marginRight around the table to the specified value. */
    public Table margin(Value pad){
        if(pad == null) throw new IllegalArgumentException("margin cannot be null.");
        padTop = pad;
        padLeft = pad;
        padBottom = pad;
        padRight = pad;
        sizeInvalid = true;
        return this;
    }

    public Table margin(Value top, Value left, Value bottom, Value right){
        if(top == null) throw new IllegalArgumentException("top cannot be null.");
        if(left == null) throw new IllegalArgumentException("left cannot be null.");
        if(bottom == null) throw new IllegalArgumentException("bottom cannot be null.");
        if(right == null) throw new IllegalArgumentException("right cannot be null.");
        padTop = top;
        padLeft = left;
        padBottom = bottom;
        padRight = right;
        sizeInvalid = true;
        return this;
    }

    /** Padding at the top edge of the table. */
    public Table marginTop(Value padTop){
        if(padTop == null) throw new IllegalArgumentException("marginTop cannot be null.");
        this.padTop = padTop;
        sizeInvalid = true;
        return this;
    }

    /** Padding at the left edge of the table. */
    public Table marginLeft(Value padLeft){
        if(padLeft == null) throw new IllegalArgumentException("marginLeft cannot be null.");
        this.padLeft = padLeft;
        sizeInvalid = true;
        return this;
    }

    /** Padding at the bottom edge of the table. */
    public Table marginBottom(Value padBottom){
        if(padBottom == null) throw new IllegalArgumentException("marginBottom cannot be null.");
        this.padBottom = padBottom;
        sizeInvalid = true;
        return this;
    }

    /** Padding at the right edge of the table. */
    public Table marginRight(Value padRight){
        if(padRight == null) throw new IllegalArgumentException("marginRight cannot be null.");
        this.padRight = padRight;
        sizeInvalid = true;
        return this;
    }

    /** Sets the marginTop, marginLeft, marginBottom, and marginRight around the table to the specified value. */
    public Table margin(float pad){
        margin(e -> (pad));
        return this;
    }

    public Table margin(float top, float left, float bottom, float right){
        padTop = e -> (top);
        padLeft = e -> (left);
        padBottom = e -> (bottom);
        padRight = e -> (right);
        sizeInvalid = true;
        return this;
    }

    /** Padding at the top edge of the table. */
    public Table marginTop(float padTop){
        this.padTop = e -> (padTop);
        sizeInvalid = true;
        return this;
    }

    /** Padding at the left edge of the table. */
    public Table marginLeft(float padLeft){
        this.padLeft = e -> (padLeft);
        sizeInvalid = true;
        return this;
    }

    /** Padding at the bottom edge of the table. */
    public Table marginBottom(float padBottom){
        this.padBottom = e -> (padBottom);
        sizeInvalid = true;
        return this;
    }

    /** Padding at the right edge of the table. */
    public Table marginRight(float padRight){
        this.padRight = e -> (padRight);
        sizeInvalid = true;
        return this;
    }

    /**
     * Alignment of the logical table within the table actor. Set to {@link Align#center}, {@link Align#top}, {@link Align#bottom}
     * , {@link Align#left}, {@link Align#right}, or any combination of those.
     */
    public Table align(int align){
        this.align = align;
        return this;
    }

    /** Sets the alignment of the logical table within the table actor to {@link Align#center}. This clears any other alignment. */
    public Table center(){
        align = Align.center;
        return this;
    }

    /** Adds {@link Align#top} and clears {@link Align#bottom} for the alignment of the logical table within the table actor. */
    public Table top(){
        align |= Align.top;
        align &= ~Align.bottom;
        return this;
    }

    /** Adds {@link Align#left} and clears {@link Align#right} for the alignment of the logical table within the table actor. */
    public Table left(){
        align |= Align.left;
        align &= ~Align.right;
        return this;
    }

    /** Adds {@link Align#bottom} and clears {@link Align#top} for the alignment of the logical table within the table actor. */
    public Table bottom(){
        align |= Align.bottom;
        align &= ~Align.top;
        return this;
    }

    /** Adds {@link Align#right} and clears {@link Align#left} for the alignment of the logical table within the table actor. */
    public Table right(){
        align |= Align.right;
        align &= ~Align.left;
        return this;
    }

    public float getMarginTop(){
        return padTop.get(this);
    }

    public float getMarginLeft(){
        return padLeft.get(this);
    }

    public float getMarginBottom(){
        return padBottom.get(this);
    }

    public float getMarginRight(){
        return padRight.get(this);
    }

    /** Returns {@link #getMarginLeft()} plus {@link #getMarginRight()}. */
    public float getMarginX(){
        return padLeft.get(this) + padRight.get(this);
    }

    /** Returns {@link #getMarginTop()} plus {@link #getMarginBottom()}. */
    public float getMarginY(){
        return padTop.get(this) + padBottom.get(this);
    }

    public int getAlign(){
        return align;
    }

    /**
     * Returns the row index for the y coordinate, or -1 if there are no cells.
     * @param y The y coordinate, where 0 is the top of the table.
     */
    public int getRow(float y){
        Array<Cell> cells = this.cells;
        int row = 0;
        y += getMarginTop();
        int i = 0, n = cells.size;
        if(n == 0) return -1;
        if(n == 1) return 0;
        while(i < n){
            Cell c = cells.get(i++);
            if(c.elementY + c.computedPadTop < y) break;
            if(c.endRow) row++;
        }
        return row;
    }

    /** If true (the default), positions and sizes are rounded to integers. */
    public void setRound(boolean round){
        this.round = round;
    }

    public int getRows(){
        return rows;
    }

    public int getColumns(){
        return columns;
    }

    /** Returns the height of the specified row. */
    public float getRowHeight(int rowIndex){
        return rowHeight[rowIndex];
    }

    /** Returns the width of the specified column. */
    public float getColumnWidth(int columnIndex){
        return columnWidth[columnIndex];
    }

    private float[] ensureSize(float[] array, int size){
        if(array == null || array.length < size) return new float[size];
        for(int i = 0, n = array.length; i < n; i++)
            array[i] = 0;
        return array;
    }

    public void layout(){
        float width = getWidth();
        float height = getHeight();

        layout(0, 0, width, height);

        Array<Cell> cells = this.cells;
        if(round){
            for(int i = 0, n = cells.size; i < n; i++){
                Cell c = cells.get(i);
                float actorWidth = Math.round(c.elementWidth);
                float actorHeight = Math.round(c.elementHeight);
                float actorX = Math.round(c.elementX);
                float actorY = height - Math.round(c.elementY) - actorHeight;
                c.setActorBounds(actorX, actorY, actorWidth, actorHeight);
                Element actor = c.element;
                if(actor != null) actor.setBounds(actorX, actorY, actorWidth, actorHeight);
            }
        }else{
            for(int i = 0, n = cells.size; i < n; i++){
                Cell c = cells.get(i);
                float actorHeight = c.elementHeight;
                float actorY = height - c.elementY - actorHeight;
                c.setActorY(actorY);
                Element actor = c.element;
                if(actor != null) actor.setBounds(c.elementX, actorY, c.elementWidth, actorHeight);
            }
        }
        // Validate children separately from sizing actors to ensure actors without a cell are validated.
        Array<Element> children = getChildren();
        for(int i = 0, n = children.size; i < n; i++){
            Element child = children.get(i);
            child.validate();
        }
    }

    @SuppressWarnings("unused")
    private void computeSize(){
        sizeInvalid = false;

        Array<Cell> cells = this.cells;
        int cellCount = cells.size;

        // Implicitly End the row for layout purposes.
        if(cellCount > 0 && !cells.peek().endRow){
            endRow();
            implicitEndRow = true;
        }

        int columns = this.columns, rows = this.rows;
        float[] columnMinWidth = this.columnMinWidth = ensureSize(this.columnMinWidth, columns);
        float[] rowMinHeight = this.rowMinHeight = ensureSize(this.rowMinHeight, rows);
        float[] columnPrefWidth = this.columnPrefWidth = ensureSize(this.columnPrefWidth, columns);
        float[] rowPrefHeight = this.rowPrefHeight = ensureSize(this.rowPrefHeight, rows);
        float[] columnWidth = this.columnWidth = ensureSize(this.columnWidth, columns);
        float[] rowHeight = this.rowHeight = ensureSize(this.rowHeight, rows);
        float[] expandWidth = this.expandWidth = ensureSize(this.expandWidth, columns);
        float[] expandHeight = this.expandHeight = ensureSize(this.expandHeight, rows);

        float spaceRightLast = 0;
        for(int i = 0; i < cellCount; i++){
            Cell c = cells.get(i);
            int column = c.column, row = c.row, colspan = c.colspan;
            Element a = c.element;

            // Collect rows that expand and colspan=1 columns that expand.
            if(c.expandY != 0 && expandHeight[row] == 0) expandHeight[row] = c.expandY;
            if(colspan == 1 && c.expandX != 0 && expandWidth[column] == 0) expandWidth[column] = c.expandX;

            // Compute combined padding/spacing for cells.
            // Spacing between actors isn't additive, the larger is used. Also, no spacing around edges.
            c.computedPadLeft = c.padLeft.get(a) + (column == 0 ? 0 : Math.max(0, c.spaceLeft.get(a) - spaceRightLast));
            c.computedPadTop = c.padTop.get(a);
            if(c.cellAboveIndex != -1){
                Cell above = cells.get(c.cellAboveIndex);
                c.computedPadTop += Math.max(0, c.spaceTop.get(a) - above.spaceBottom.get(a));
            }
            float spaceRight = c.spaceRight.get(a);
            c.computedPadRight = c.padRight.get(a) + ((column + colspan) == columns ? 0 : spaceRight);
            c.computedPadBottom = c.padBottom.get(a) + (row == rows - 1 ? 0 : c.spaceBottom.get(a));
            spaceRightLast = spaceRight;

            // Determine minimum and preferred cell sizes.
            float prefWidth = Value.prefWidth.get(a);
            float prefHeight = Value.prefHeight.get(a);
            float minWidth = c.minWidth.get(a);
            float minHeight = c.minHeight.get(a);
            float maxWidth = c.maxWidth.get(a);
            float maxHeight = c.maxHeight.get(a);
            if(prefWidth < minWidth) prefWidth = minWidth;
            if(prefHeight < minHeight) prefHeight = minHeight;
            if(maxWidth > 0 && prefWidth > maxWidth) prefWidth = maxWidth;
            if(maxHeight > 0 && prefHeight > maxHeight) prefHeight = maxHeight;

            if(colspan == 1){ // Spanned column min and pref width is added later.
                float hpadding = c.computedPadLeft + c.computedPadRight;
                columnPrefWidth[column] = Math.max(columnPrefWidth[column], prefWidth + hpadding);
                columnMinWidth[column] = Math.max(columnMinWidth[column], minWidth + hpadding);
            }
            float vpadding = c.computedPadTop + c.computedPadBottom;
            rowPrefHeight[row] = Math.max(rowPrefHeight[row], prefHeight + vpadding);
            rowMinHeight[row] = Math.max(rowMinHeight[row], minHeight + vpadding);
        }

        float uniformMinWidth = 0, uniformMinHeight = 0;
        float uniformPrefWidth = 0, uniformPrefHeight = 0;
        for(int i = 0; i < cellCount; i++){
            Cell c = cells.get(i);
            int column = c.column;

            // Colspan with expand will expand all spanned columns if none of the spanned columns have expand.
            int expandX = c.expandX;
            outer:
            if(expandX != 0){
                int nn = column + c.colspan;
                for(int ii = column; ii < nn; ii++)
                    if(expandWidth[ii] != 0) break outer;
                for(int ii = column; ii < nn; ii++)
                    expandWidth[ii] = expandX;
            }

            // Collect uniform sizes.
            if(c.uniformX && c.colspan == 1){
                float hpadding = c.computedPadLeft + c.computedPadRight;
                uniformMinWidth = Math.max(uniformMinWidth, columnMinWidth[column] - hpadding);
                uniformPrefWidth = Math.max(uniformPrefWidth, columnPrefWidth[column] - hpadding);
            }
            if(c.uniformY){
                float vpadding = c.computedPadTop + c.computedPadBottom;
                uniformMinHeight = Math.max(uniformMinHeight, rowMinHeight[c.row] - vpadding);
                uniformPrefHeight = Math.max(uniformPrefHeight, rowPrefHeight[c.row] - vpadding);
            }
        }

        // Size uniform cells to the same width/height.
        if(uniformPrefWidth > 0 || uniformPrefHeight > 0){
            for(int i = 0; i < cellCount; i++){
                Cell c = cells.get(i);
                if(uniformPrefWidth > 0 && c.uniformX && c.colspan == 1){
                    float hpadding = c.computedPadLeft + c.computedPadRight;
                    columnMinWidth[c.column] = uniformMinWidth + hpadding;
                    columnPrefWidth[c.column] = uniformPrefWidth + hpadding;
                }
                if(uniformPrefHeight > 0 && c.uniformY){
                    float vpadding = c.computedPadTop + c.computedPadBottom;
                    rowMinHeight[c.row] = uniformMinHeight + vpadding;
                    rowPrefHeight[c.row] = uniformPrefHeight + vpadding;
                }
            }
        }

        // Distribute any additional min and pref width added by colspanned cells to the columns spanned.
        for(int i = 0; i < cellCount; i++){
            Cell c = cells.get(i);
            int colspan = c.colspan;
            if(colspan == 1) continue;
            int column = c.column;

            Element a = c.element;
            float minWidth = c.minWidth.get(a);
            float prefWidth = Value.prefWidth.get(a);
            float maxWidth = c.maxWidth.get(a);
            if(prefWidth < minWidth) prefWidth = minWidth;
            if(maxWidth > 0 && prefWidth > maxWidth) prefWidth = maxWidth;

            float spannedMinWidth = -(c.computedPadLeft + c.computedPadRight), spannedPrefWidth = spannedMinWidth;
            float totalExpandWidth = 0;
            for(int ii = column, nn = ii + colspan; ii < nn; ii++){
                spannedMinWidth += columnMinWidth[ii];
                spannedPrefWidth += columnPrefWidth[ii];
                totalExpandWidth += expandWidth[ii]; // Distribute extra space using expand, if any columns have expand.
            }

            float extraMinWidth = Math.max(0, minWidth - spannedMinWidth);
            float extraPrefWidth = Math.max(0, prefWidth - spannedPrefWidth);
            for(int ii = column, nn = ii + colspan; ii < nn; ii++){
                float ratio = totalExpandWidth == 0 ? 1f / colspan : expandWidth[ii] / totalExpandWidth;
                columnMinWidth[ii] += extraMinWidth * ratio;
                columnPrefWidth[ii] += extraPrefWidth * ratio;
            }
        }

        // Determine table min and pref size.
        tableMinWidth = 0;
        tableMinHeight = 0;
        tablePrefWidth = 0;
        tablePrefHeight = 0;
        for(int i = 0; i < columns; i++){
            tableMinWidth += columnMinWidth[i];
            tablePrefWidth += columnPrefWidth[i];
        }
        for(int i = 0; i < rows; i++){
            tableMinHeight += rowMinHeight[i];
            tablePrefHeight += Math.max(rowMinHeight[i], rowPrefHeight[i]);
        }
        float hpadding = padLeft.get(this) + padRight.get(this);
        float vpadding = padTop.get(this) + padBottom.get(this);
        tableMinWidth = tableMinWidth + hpadding;
        tableMinHeight = tableMinHeight + vpadding;
        tablePrefWidth = Math.max(tablePrefWidth + hpadding, tableMinWidth);
        tablePrefHeight = Math.max(tablePrefHeight + vpadding, tableMinHeight);
    }

    /**
     * Positions and sizes children of the table using the cell associated with each child. The values given are the position
     * within the parent and size of the table.
     */
    private void layout(float layoutX, float layoutY, float layoutWidth, float layoutHeight){
        Array<Cell> cells = this.cells;
        int cellCount = cells.size;

        if(sizeInvalid) computeSize();

        float padLeft = this.padLeft.get(this);
        float hpadding = padLeft + padRight.get(this);
        float padTop = this.padTop.get(this);
        float vpadding = padTop + padBottom.get(this);

        int columns = this.columns, rows = this.rows;
        float[] expandWidth = this.expandWidth, expandHeight = this.expandHeight;
        float[] columnWidth = this.columnWidth, rowHeight = this.rowHeight;

        float totalExpandWidth = 0, totalExpandHeight = 0;
        for(int i = 0; i < columns; i++)
            totalExpandWidth += expandWidth[i];
        for(int i = 0; i < rows; i++)
            totalExpandHeight += expandHeight[i];

        // Size columns and rows between min and pref size using (preferred - min) size to weight distribution of extra space.
        float[] columnWeightedWidth;
        float totalGrowWidth = tablePrefWidth - tableMinWidth;
        if(totalGrowWidth == 0)
            columnWeightedWidth = columnMinWidth;
        else{
            float extraWidth = Math.min(totalGrowWidth, Math.max(0, layoutWidth - tableMinWidth));
            columnWeightedWidth = Table.columnWeightedWidth = ensureSize(Table.columnWeightedWidth, columns);
            float[] columnMinWidth = this.columnMinWidth, columnPrefWidth = this.columnPrefWidth;
            for(int i = 0; i < columns; i++){
                float growWidth = columnPrefWidth[i] - columnMinWidth[i];
                float growRatio = growWidth / totalGrowWidth;
                columnWeightedWidth[i] = columnMinWidth[i] + extraWidth * growRatio;
            }
        }

        float[] rowWeightedHeight;
        float totalGrowHeight = tablePrefHeight - tableMinHeight;
        if(totalGrowHeight == 0)
            rowWeightedHeight = rowMinHeight;
        else{
            rowWeightedHeight = Table.rowWeightedHeight = ensureSize(Table.rowWeightedHeight, rows);
            float extraHeight = Math.min(totalGrowHeight, Math.max(0, layoutHeight - tableMinHeight));
            float[] rowMinHeight = this.rowMinHeight, rowPrefHeight = this.rowPrefHeight;
            for(int i = 0; i < rows; i++){
                float growHeight = rowPrefHeight[i] - rowMinHeight[i];
                float growRatio = growHeight / totalGrowHeight;
                rowWeightedHeight[i] = rowMinHeight[i] + extraHeight * growRatio;
            }
        }

        // Determine actor and cell sizes (before expand or fill).
        for(int i = 0; i < cellCount; i++){
            Cell c = cells.get(i);
            int column = c.column, row = c.row;
            Element a = c.element;

            float spannedWeightedWidth = 0;
            int colspan = c.colspan;
            for(int ii = column, nn = ii + colspan; ii < nn; ii++)
                spannedWeightedWidth += columnWeightedWidth[ii];
            float weightedHeight = rowWeightedHeight[row];

            float prefWidth = Value.prefWidth.get(a);
            float prefHeight = Value.prefHeight.get(a);
            float minWidth = c.minWidth.get(a);
            float minHeight = c.minHeight.get(a);
            float maxWidth = c.maxWidth.get(a);
            float maxHeight = c.maxHeight.get(a);
            if(prefWidth < minWidth) prefWidth = minWidth;
            if(prefHeight < minHeight) prefHeight = minHeight;
            if(maxWidth > 0 && prefWidth > maxWidth) prefWidth = maxWidth;
            if(maxHeight > 0 && prefHeight > maxHeight) prefHeight = maxHeight;

            c.elementWidth = Math.min(spannedWeightedWidth - c.computedPadLeft - c.computedPadRight, prefWidth);
            c.elementHeight = Math.min(weightedHeight - c.computedPadTop - c.computedPadBottom, prefHeight);

            if(colspan == 1) columnWidth[column] = Math.max(columnWidth[column], spannedWeightedWidth);
            rowHeight[row] = Math.max(rowHeight[row], weightedHeight);
        }

        // Distribute remaining space to any expanding columns/rows.
        if(totalExpandWidth > 0){
            float extra = layoutWidth - hpadding;
            for(int i = 0; i < columns; i++)
                extra -= columnWidth[i];
            float used = 0;
            int lastIndex = 0;
            for(int i = 0; i < columns; i++){
                if(expandWidth[i] == 0) continue;
                float amount = extra * expandWidth[i] / totalExpandWidth;
                columnWidth[i] += amount;
                used += amount;
                lastIndex = i;
            }
            columnWidth[lastIndex] += extra - used;
        }
        if(totalExpandHeight > 0){
            float extra = layoutHeight - vpadding;
            for(int i = 0; i < rows; i++)
                extra -= rowHeight[i];
            float used = 0;
            int lastIndex = 0;
            for(int i = 0; i < rows; i++){
                if(expandHeight[i] == 0) continue;
                float amount = extra * expandHeight[i] / totalExpandHeight;
                rowHeight[i] += amount;
                used += amount;
                lastIndex = i;
            }
            rowHeight[lastIndex] += extra - used;
        }

        // Distribute any additional width added by colspanned cells to the columns spanned.
        for(int i = 0; i < cellCount; i++){
            Cell c = cells.get(i);
            int colspan = c.colspan;
            if(colspan == 1) continue;

            float extraWidth = 0;
            for(int column = c.column, nn = column + colspan; column < nn; column++)
                extraWidth += columnWeightedWidth[column] - columnWidth[column];
            extraWidth -= Math.max(0, c.computedPadLeft + c.computedPadRight);

            extraWidth /= colspan;
            if(extraWidth > 0){
                for(int column = c.column, nn = column + colspan; column < nn; column++)
                    columnWidth[column] += extraWidth;
            }
        }

        // Determine table size.
        float tableWidth = hpadding, tableHeight = vpadding;
        for(int i = 0; i < columns; i++)
            tableWidth += columnWidth[i];
        for(int i = 0; i < rows; i++)
            tableHeight += rowHeight[i];

        // Position table within the container.
        int align = this.align;
        float x = layoutX + padLeft;
        if((align & Align.right) != 0)
            x += layoutWidth - tableWidth;
        else if((align & Align.left) == 0) // Center
            x += (layoutWidth - tableWidth) / 2;

        float y = layoutY + padTop;
        if((align & Align.bottom) != 0)
            y += layoutHeight - tableHeight;
        else if((align & Align.top) == 0) // Center
            y += (layoutHeight - tableHeight) / 2;

        // Position actors within cells.
        float currentX = x, currentY = y;
        for(int i = 0; i < cellCount; i++){
            Cell c = cells.get(i);

            float spannedCellWidth = 0;
            for(int column = c.column, nn = column + c.colspan; column < nn; column++)
                spannedCellWidth += columnWidth[column];
            spannedCellWidth -= c.computedPadLeft + c.computedPadRight;

            currentX += c.computedPadLeft;

            float fillX = c.fillX, fillY = c.fillY;
            if(fillX > 0){
                c.elementWidth = Math.max(spannedCellWidth * fillX, c.minWidth.get(c.element));
                float maxWidth = c.maxWidth.get(c.element);
                if(maxWidth > 0) c.elementWidth = Math.min(c.elementWidth, maxWidth);
            }
            if(fillY > 0){
                c.elementHeight = Math.max(rowHeight[c.row] * fillY - c.computedPadTop - c.computedPadBottom, c.minHeight.get(c.element));
                float maxHeight = c.maxHeight.get(c.element);
                if(maxHeight > 0) c.elementHeight = Math.min(c.elementHeight, maxHeight);
            }

            align = c.align;
            if((align & Align.left) != 0)
                c.elementX = currentX;
            else if((align & Align.right) != 0)
                c.elementX = currentX + spannedCellWidth - c.elementWidth;
            else
                c.elementX = currentX + (spannedCellWidth - c.elementWidth) / 2;

            if((align & Align.top) != 0)
                c.elementY = currentY + c.computedPadTop;
            else if((align & Align.bottom) != 0)
                c.elementY = currentY + rowHeight[c.row] - c.elementHeight - c.computedPadBottom;
            else
                c.elementY = currentY + (rowHeight[c.row] - c.elementHeight + c.computedPadTop - c.computedPadBottom) / 2;

            if(c.endRow){
                currentX = x;
                currentY += rowHeight[c.row];
            }else
                currentX += spannedCellWidth + c.computedPadRight;
        }
    }

    /** @author Nathan Sweet */
    public enum Debug{
        none, all, table, cell, actor
    }

    public interface DrawRect{
        void draw(float x, float y, float width, float height);
    }
}
