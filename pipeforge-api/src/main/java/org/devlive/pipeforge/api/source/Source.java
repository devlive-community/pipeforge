/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */
package org.devlive.pipeforge.api.source;

import java.io.Serializable;
import java.util.List;
import org.devlive.pipeforge.api.annotation.Stable;
import org.devlive.pipeforge.api.serialization.VersionedSerializer;
import org.devlive.pipeforge.api.table.CatalogTable;

/**
 * A source of data: one enumerator that discovers splits and parallel readers that read them.
 * Batch, streaming and CDC sources implement the same interface and differ in {@link
 * #boundedness()} and their splits.
 */
@Stable
public interface Source<SplitT extends SourceSplit, EnumStateT> extends Serializable {

    Boundedness boundedness();

    /** The tables this source produces; called while planning. */
    List<CatalogTable> producedTables(SourceContext context);

    SplitEnumerator<SplitT, EnumStateT> createEnumerator(EnumeratorContext<SplitT> context);

    SplitEnumerator<SplitT, EnumStateT> restoreEnumerator(EnumeratorContext<SplitT> context, EnumStateT state);

    SourceReader<SplitT> createReader(ReaderContext context);

    VersionedSerializer<SplitT> splitSerializer();

    VersionedSerializer<EnumStateT> enumeratorStateSerializer();
}
