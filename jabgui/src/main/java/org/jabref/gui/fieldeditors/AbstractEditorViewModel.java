package org.jabref.gui.fieldeditors;

import java.util.Collection;
import java.time.Duration;

import javax.swing.undo.UndoManager;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import org.jabref.gui.AbstractViewModel;
import org.jabref.gui.autocompleter.SuggestionProvider;
import org.jabref.gui.undo.UndoableFieldChange;
import org.jabref.gui.util.BindingsHelper;
import org.jabref.logic.integrity.FieldCheckers;
import org.jabref.logic.integrity.ValueChecker;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;

import com.tobiasdiez.easybind.EasyBind;
import com.tobiasdiez.easybind.EasyObservableValue;
import com.tobiasdiez.easybind.Subscription;
import de.saxsys.mvvmfx.utils.validation.CompositeValidator;
import de.saxsys.mvvmfx.utils.validation.FunctionBasedValidator;
import de.saxsys.mvvmfx.utils.validation.ValidationMessage;
import de.saxsys.mvvmfx.utils.validation.Validator;
import org.controlsfx.control.textfield.AutoCompletionBinding;
import org.reactfx.util.FxTimer;
import org.reactfx.util.Timer;

public class AbstractEditorViewModel extends AbstractViewModel {
    private static final long FIELD_UPDATE_DEBOUNCE_MS = 300; // 300ms debounce delay
    
    protected final Field field;
    protected StringProperty text = new SimpleStringProperty("");
    protected BibEntry entry;
    private final SuggestionProvider<?> suggestionProvider;
    private final UndoManager undoManager;
    private final CompositeValidator fieldValidator;
    private EasyObservableValue<String> fieldBinding;
    
    // Debouncing support
    private Timer debounceTimer;
    private String pendingValue;
    private Subscription textSubscription;

    public AbstractEditorViewModel(Field field, SuggestionProvider<?> suggestionProvider, FieldCheckers fieldCheckers, UndoManager undoManager) {
        this.field = field;
        this.suggestionProvider = suggestionProvider;
        this.undoManager = undoManager;

        this.fieldValidator = new CompositeValidator();
        for (ValueChecker checker : fieldCheckers.getForField(field)) {
            FunctionBasedValidator<String> validator = new FunctionBasedValidator<>(text, value ->
                    checker.checkValue(value).map(ValidationMessage::warning).orElse(null));
            fieldValidator.addValidators(validator);
        }
    }

    public Validator getFieldValidator() {
        return fieldValidator;
    }

    public StringProperty textProperty() {
        return text;
    }

    public void bindToEntry(BibEntry entry) {
        this.entry = entry;

        // We need to keep a reference to the binding since it otherwise gets discarded
        fieldBinding = entry.getFieldBinding(field).asOrdinary();

        // Create debounced timer for field updates
        debounceTimer = FxTimer.create(Duration.ofMillis(FIELD_UPDATE_DEBOUNCE_MS), this::applyPendingFieldUpdate);

        // Subscribe to text changes for debounced updates
        textSubscription = EasyBind.subscribe(this.textProperty(), newValue -> {
            if (newValue != null) {
                pendingValue = newValue;
                // Restart the timer - this will cancel any pending update and schedule a new one
                debounceTimer.restart();
            }
        });

        // One-way binding from entry to text (immediate, no debounce needed)
        EasyBind.subscribe(fieldBinding, text::setValue);
    }

    /**
     * Applies the pending field value update to the entry.
     * This is called after the debounce delay expires.
     */
    private void applyPendingFieldUpdate() {
        if (pendingValue == null || entry == null) {
            return;
        }

        // A file may be loaded using CRLF. ControlsFX uses hardcoded \n for multiline fields.
        // Thus, we need to normalize the line endings.
        // Note: Normalizing for the .bib file is done during writing of the .bib file (see org.jabref.logic.exporter.BibWriter.BibWriter).
        String oldValue = entry.getField(field).map(value -> value.replace("\r\n", "\n")).orElse(null);
        if (!pendingValue.equals(oldValue)) {
            entry.setField(field, pendingValue);
            undoManager.addEdit(new UndoableFieldChange(entry, field, oldValue, pendingValue));
        }
        
        pendingValue = null;
    }

    /**
     * Immediately applies any pending field update.
     * This should be called when the field loses focus to ensure changes are saved.
     */
    public void flushPendingUpdate() {
        if (debounceTimer != null) {
            debounceTimer.stop();
        }
        applyPendingFieldUpdate();
    }

    /**
     * Unbinds from the entry and cleans up resources.
     */
    public void unbind() {
        // Apply any pending update before unbinding
        flushPendingUpdate();
        
        if (textSubscription != null) {
            textSubscription.unsubscribe();
            textSubscription = null;
        }
        
        if (debounceTimer != null) {
            debounceTimer.stop();
            debounceTimer = null;
        }
        
        pendingValue = null;
        entry = null;
        fieldBinding = null;
    }

    public Collection<?> complete(AutoCompletionBinding.ISuggestionRequest request) {
        return suggestionProvider.provideSuggestions(request);
    }
}
