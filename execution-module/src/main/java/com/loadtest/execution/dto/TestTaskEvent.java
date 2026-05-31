package com.loadtest.execution.dto;

import java.io.Serializable;

public record TestTaskEvent(String taskId) implements Serializable {
}
