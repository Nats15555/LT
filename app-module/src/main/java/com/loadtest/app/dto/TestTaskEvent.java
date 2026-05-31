package com.loadtest.app.dto;

import java.io.Serializable;

public record TestTaskEvent(String taskId) implements Serializable {
}
