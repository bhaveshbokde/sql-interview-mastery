package com.bhavesh.sqlmastery.error;

/** Thrown when a lesson, problem or quiz item does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String kind, String id) {
        super("No " + kind + " with id or slug \"" + id + "\".");
    }
}
