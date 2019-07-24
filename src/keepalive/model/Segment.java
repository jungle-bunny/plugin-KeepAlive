/*
 * Keep Alive Plugin
 * Copyright (C) 2012 Jeriadoc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package keepalive.model;

import keepalive.service.reinserter.Reinserter;

public class Segment {

    private int id;
    private final int size;
    private Block[] blocks;
    private int dataBlocksCount;
    private int success = 0;
    private int failed = 0;
    private boolean persistenceCheckOk = false;
    private boolean healingNotPossible = false;

    private final Reinserter reinserter;

    public Segment(Reinserter reinserter, int id, int size) {
        this.reinserter = reinserter;
        this.id = id;
        this.size = size;
        blocks = new Block[size];
    }

    public Block getBlock(int id) {
        return blocks[id];
    }

    public void addBlock(Block block) {
        blocks[block.getId()] = block;
        if (block.isDataBlock()) {
            dataBlocksCount++;
        }
    }

    public Block getDataBlock(int id) {
        return blocks[id];
    }

    public Block getCheckBlock(int id) {
        return blocks[dataSize() + id];
    }

    public int size() {
        return size; // blocks.length can produce null-exception (see isFinished())
    }

    public int dataSize() {
        return dataBlocksCount;
    }

    public int checkSize() {
        return size - dataBlocksCount;
    }

    public void initInsert() {
        success = 0;
        failed = 0;
    }

    public void regFetchSuccess(double persistenceRate) {
        persistenceCheckOk = true;
        success = (int) Math.round(persistenceRate * size);
        failed = size - success;
        reinserter.updateBlockStatistic(id, success, failed);
    }

    public void regFetchSuccess(boolean isSuccess) {
        if (isSuccess)
            success++;
        else
            failed++;

        reinserter.updateBlockStatistic(id, success, failed);
    }

    public int getId() {
        return id;
    }

    public boolean isFinished() {
        if (blocks == null)
            return true;

        boolean finished = true;
        if (!persistenceCheckOk && !healingNotPossible) {
            if (size == 1) {
                finished = getBlock(0).isInsertDone();
            } else {
                for (int i = 0; i < size; i++) {
                    if (!getBlock(i).isFetchSuccessful() && !getBlock(i).isInsertDone()) {
                        finished = false;
                        break;
                    }
                }
            }
        }

        // free blocks (especially buckets)
        if (finished) {
            for (int i = 0; i < size; i++) {
                if (blocks[i].getBucket() != null) {
                    blocks[i].getBucket().free();
                }
            }
            blocks = null;
        }

        return finished;
    }

    public void setHealingNotPossible(boolean notPossible) {
        healingNotPossible = notPossible;
    }
}
