package org.jabref.gui.maintable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.util.Callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This resize policy is almost the same as {@link TableView#CONSTRAINED_RESIZE_POLICY}
 * We make sure that the width of all columns sums up to the total width of the table.
 * However, in contrast to {@link TableView#CONSTRAINED_RESIZE_POLICY} we size the columns initially by their preferred width.
 * Although {@link TableView#CONSTRAINED_RESIZE_POLICY} is deprecated, this policy maintains a similar resizing behavior.
 * 
 * This policy allows manual column resizing while still maintaining auto-fit behavior when the table is resized.
 */
public class SmartConstrainedResizePolicy implements Callback<TableView.ResizeFeatures, Boolean> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmartConstrainedResizePolicy.class);

    @Override
    public Boolean call(TableView.ResizeFeatures prop) {
        if (prop.getColumn() == null) {
            // Table was resized - auto-fit columns
            return initColumnSize(prop.getTable());
        } else {
            // Column is being resized manually
            TableColumnBase<?, ?> column = prop.getColumn();
            
            // If column is not resizable, don't allow resize
            if (!column.isResizable()) {
                return true; // Handled (prevent resize)
            }
            
            // For resizable columns, allow manual resizing by returning false
            // This lets JavaFX handle the resize normally using default behavior
            // The constrained behavior will kick in on the next table resize
            return false;
        }
    }

    private Boolean initColumnSize(TableView<?> table) {
        double tableWidth = getContentWidth(table);
        List<? extends TableColumnBase<?, ?>> visibleLeafColumns = table.getVisibleLeafColumns();
        double totalWidth = visibleLeafColumns.stream().mapToDouble(TableColumnBase::getWidth).sum();

        if (Math.abs(totalWidth - tableWidth) > 1) {
            double totalPrefWidth = visibleLeafColumns.stream().mapToDouble(TableColumnBase::getPrefWidth).sum();
            double currPrefWidth = 0;
            if (totalPrefWidth > 0) {
                for (TableColumnBase<?, ?> col : visibleLeafColumns) {
                    double share = col.getPrefWidth() / totalPrefWidth;
                    double newSize = tableWidth * share;

                    // Just to make sure that we are staying under the total table width (due to rounding errors)
                    currPrefWidth += newSize;
                    if (currPrefWidth > tableWidth) {
                        newSize -= currPrefWidth - tableWidth;
                        currPrefWidth -= tableWidth;
                    }

                    resize(col, newSize - col.getWidth());
                }
            }
        }

        return false;
    }

    private void resize(TableColumnBase<?, ?> column, double delta) {
        // We have to use reflection since TableUtil is not visible to us
        try {
            // TODO: reflective access, should be removed
            Class<?> clazz = Class.forName("javafx.scene.control.TableUtil");
            Method resizeMethod = clazz.getDeclaredMethod("resize", TableColumnBase.class, double.class);
            resizeMethod.setAccessible(true);
            resizeMethod.invoke(null, column, delta);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
            LOGGER.error("Could not invoke resize in TableUtil", e);
            // Fallback: directly set the width if reflection fails
            column.setPrefWidth(column.getWidth() + delta);
        }
    }

    private Double getContentWidth(TableView<?> table) {
        try {
            // TODO: reflective access, should be removed
            Field privateStringField = TableView.class.getDeclaredField("contentWidth");
            privateStringField.setAccessible(true);
            return (Double) privateStringField.get(table);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            // Fallback: use visible width if reflection fails
            return table.getWidth();
        }
    }
}
