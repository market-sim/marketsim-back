package tn.esprit.marketsim.user.activity;

import java.util.ArrayList;
import java.util.List;

/** Details surs d'une operation sensible (pas de mots de passe / tokens). */
public final class TraceDetails {

    private final List<FieldChange> changes = new ArrayList<>();
    private boolean passwordChanged;

    public static TraceDetails empty() {
        return new TraceDetails();
    }

    public TraceDetails add(String field, Object before, Object after) {
        String beforeVal = before == null ? null : String.valueOf(before);
        String afterVal = after == null ? null : String.valueOf(after);
        if (beforeVal == null && afterVal == null) {
            return this;
        }
        if (beforeVal != null && beforeVal.equals(afterVal)) {
            return this;
        }
        changes.add(new FieldChange(field, beforeVal, afterVal));
        return this;
    }

    public TraceDetails passwordChanged(boolean value) {
        this.passwordChanged = value;
        return this;
    }

    public List<FieldChange> changes() {
        return List.copyOf(changes);
    }

    public boolean passwordChanged() {
        return passwordChanged;
    }

    public record FieldChange(String field, String before, String after) {
    }
}
