# Project build

The root Maven project builds `core-java` and the framework-neutral `backend-java` ports together. The backend module deliberately has no Spring dependency yet; that keeps the first compile focused on the storage boundary. When the API contract is stable, add Spring Boot Web and wire a thin controller.
