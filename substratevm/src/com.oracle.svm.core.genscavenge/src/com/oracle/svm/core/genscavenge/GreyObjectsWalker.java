/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.genscavenge;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.AlwaysInline;
import com.oracle.svm.core.annotate.NeverInline;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.util.VMError;

/**
 * Apply an ObjectVisitor to all the new Object in a Space since a snapshot.
 *
 * This knows that allocations take place from the last HeapChunks of the Space. And it knows (too
 * much about) that AlignedChunks have a top pointer.
 */
/* TODO: Does this know too much about the internals of AlignedChunks? */
/* TODO: Should there be a corresponding class in AlignedChunk? */
public final class GreyObjectsWalker {
    /* The Space that is snapshot. */
    private Space space;
    /* The top of the Space, as Pointers rather than HeapChunks. */
    private AlignedHeapChunk.AlignedHeader alignedHeapChunk;
    private Pointer alignedTop;
    private UnalignedHeapChunk.UnalignedHeader unalignedHeapChunk;

    /** A factory for an instance that will be initialized lazily. */
    @Platforms(Platform.HOSTED_ONLY.class)
    public static GreyObjectsWalker factory() {
        return new GreyObjectsWalker();
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    private GreyObjectsWalker() {
        /* Nothing to do. */
    }

    /**
     * Take a snapshot of a Space, such that all Object in the Space are now black, and any new
     * Objects in the Space will be grey, and can have an ObjectVisitor applied to them.
     *
     * @param s The Space to snapshot.
     */
    void setScanStart(final Space s) {
        final Log trace = Log.noopLog().string("[Space.GreyObjectsWalker.setScanStart:").string("  s: ").string(s.getName());
        /* Remember the snapshot "constants". */
        space = s;
        final AlignedHeapChunk.AlignedHeader aChunk = s.getLastAlignedHeapChunk();
        alignedHeapChunk = aChunk;
        trace.string("  alignedHeapChunk: ").hex(alignedHeapChunk).string("  isNull: ").bool(aChunk.isNull());
        alignedTop = (aChunk.isNonNull() ? aChunk.getTop() : WordFactory.nullPointer());
        trace.string("  alignedTop: ").hex(alignedTop);
        final UnalignedHeapChunk.UnalignedHeader uChunk = s.getLastUnalignedHeapChunk();
        unalignedHeapChunk = uChunk;
        trace.string("  unalignedChunkPointer: ").hex(unalignedHeapChunk).string("]").newline();
    }

    /**
     * Compare the snapshot to the current state of the Space to see if there are grey Objects.
     *
     * @return True if the snapshot updated, false otherwise.
     */
    @AlwaysInline("GC performance")
    protected boolean haveGreyObjects() {
        return alignedHeapChunk.notEqual(space.getLastAlignedHeapChunk()) || alignedHeapChunk.isNonNull() && alignedTop.notEqual(alignedHeapChunk.getTop()) ||
                        unalignedHeapChunk.notEqual(space.getLastUnalignedHeapChunk());
    }

    @NeverInline("Split the GC into reasonable compilation units")
    void walkGreyObjects() {
        while (haveGreyObjects()) {
            walkAlignedGreyObjects();
            walkUnalignedGreyObjects();
        }
    }

    @AlwaysInline("GC performance")
    private void walkAlignedGreyObjects() {
        /* Locals that start from the snapshot. */
        AlignedHeapChunk.AlignedHeader aChunk = WordFactory.nullPointer();
        Pointer aOffset = WordFactory.nullPointer();
        if (alignedHeapChunk.isNull() && alignedTop.isNull()) {
            /* If the snapshot is empty, then I have to walk from the beginning of the Space. */
            aChunk = space.getFirstAlignedHeapChunk();
            aOffset = (aChunk.isNonNull() ? AlignedHeapChunk.getAlignedHeapChunkStart(aChunk) : WordFactory.nullPointer());
        } else {
            /* Otherwise walk Objects that arrived after the snapshot. */
            aChunk = alignedHeapChunk;
            aOffset = alignedTop;
        }
        /* Visit Objects in the AlignedChunks. */
        GreyToBlackObjectVisitor visitor = GCImpl.getGCImpl().getGreyToBlackObjectVisitor();
        if (aChunk.isNonNull()) {
            AlignedHeapChunk.AlignedHeader lastChunk;
            do {
                lastChunk = aChunk;
                if (!AlignedHeapChunk.walkObjectsFromInline(aChunk, aOffset, visitor)) {
                    throw VMError.shouldNotReachHere();
                }
                aChunk = aChunk.getNext();
                aOffset = (aChunk.isNonNull() ? AlignedHeapChunk.getAlignedHeapChunkStart(aChunk) : WordFactory.nullPointer());
            } while (aChunk.isNonNull());

            /* Move the scan point. */
            alignedHeapChunk = lastChunk;
            alignedTop = lastChunk.getTop();
        }
    }

    @AlwaysInline("GC performance")
    private void walkUnalignedGreyObjects() {
        /* Visit the Objects in the UnalignedChunk after the snapshot UnalignedChunk. */
        UnalignedHeapChunk.UnalignedHeader uChunk;
        if (unalignedHeapChunk.isNull()) {
            uChunk = space.getFirstUnalignedHeapChunk();
        } else {
            uChunk = unalignedHeapChunk.getNext();
        }
        GreyToBlackObjectVisitor visitor = GCImpl.getGCImpl().getGreyToBlackObjectVisitor();
        if (uChunk.isNonNull()) {
            UnalignedHeapChunk.UnalignedHeader lastChunk;
            do {
                lastChunk = uChunk;
                if (!UnalignedHeapChunk.walkObjectsFromInline(uChunk, UnalignedHeapChunk.getUnalignedHeapChunkStart(uChunk), visitor)) {
                    throw VMError.shouldNotReachHere();
                }
                uChunk = uChunk.getNext();
            } while (uChunk.isNonNull());

            /* Move the scan point. */
            unalignedHeapChunk = lastChunk;
        }
    }
}
