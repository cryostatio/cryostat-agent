/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.agent.remote;

import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor.RecordingState;

import jdk.jfr.Recording;

class SerializableRecording {

    protected long id;
    protected String name;
    protected RecordingState state;
    protected long startTime;
    protected long duration;
    protected boolean continuous;
    protected boolean toDisk;
    protected long maxSize;
    protected long maxAge;

    SerializableRecording(Recording recording) {
        this(
                recording.getId(),
                recording.getName(),
                mapState(recording.getState()),
                recording.getStartTime() == null ? 0 : recording.getStartTime().toEpochMilli(),
                recording.getDuration() == null ? 0 : recording.getDuration().toMillis(),
                recording.getDuration() == null,
                recording.isToDisk(),
                recording.getMaxSize(),
                recording.getMaxAge() == null ? 0 : recording.getMaxAge().toMillis());
    }

    private static RecordingState mapState(jdk.jfr.RecordingState s) {
        switch (s) {
            case NEW:
                return RecordingState.CREATED;
            case DELAYED:
                return RecordingState.CREATED;
            case RUNNING:
                return RecordingState.RUNNING;
            case STOPPED:
                return RecordingState.STOPPED;
            default:
                return null;
        }
    }

    SerializableRecording(
            long id,
            String name,
            RecordingState state,
            long startTime,
            long duration,
            boolean continuous,
            boolean toDisk,
            long maxSize,
            long maxAge) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.startTime = startTime;
        this.duration = duration;
        this.continuous = continuous;
        this.toDisk = toDisk;
        this.maxSize = maxSize;
        this.maxAge = maxAge;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RecordingState getState() {
        return state;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isContinuous() {
        return continuous;
    }

    public boolean isToDisk() {
        return toDisk;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public long getMaxAge() {
        return maxAge;
    }
}
