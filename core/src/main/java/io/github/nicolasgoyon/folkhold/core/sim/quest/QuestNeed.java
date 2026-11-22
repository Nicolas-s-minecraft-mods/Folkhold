package io.github.nicolasgoyon.folkhold.core.sim.quest;

import io.github.nicolasgoyon.folkhold.core.sim.SerializedName;
import java.util.Optional;

/** The need that makes the offers of a quest definition (see {@link QuestDefinition}). */
public enum QuestNeed implements SerializedName {
    /** The inputs of the active jobs of a village with a low stock. Kind {@link QuestKind#DELIVERY}. */
    JOB_INPUTS("job_inputs", QuestKind.DELIVERY),
    /** The materials of a construction site that the stock does not have. Kind {@link QuestKind#DELIVERY}. */
    SITE_MATERIALS("site_materials", QuestKind.DELIVERY),
    /** A new caravan that waits for an escort. Kind {@link QuestKind#ESCORT}. */
    CARAVAN("caravan", QuestKind.ESCORT);

    private final String serializedName;
    private final QuestKind kind;

    QuestNeed(String serializedName, QuestKind kind) {
        this.serializedName = serializedName;
        this.kind = kind;
    }

    /** The name in the definition files. */
    @Override
    public String serializedName() {
        return serializedName;
    }

    /** The kind of the quests of this need. */
    public QuestKind kind() {
        return kind;
    }

    /**
     * The need with a serialized name.
     *
     * @param name the name in a definition file
     * @return the need, or empty for an unknown name
     */
    public static Optional<QuestNeed> fromSerializedName(String name) {
        return SerializedName.lookup(QuestNeed.class, name);
    }
}
