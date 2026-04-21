# Smart Campus Sensor & Room Management API

A RESTful API built with **JAX-RS (Jersey)** and an embedded **Grizzly HTTP server** for managing campus rooms and IoT sensors.

---

## API Overview

The API follows RESTful principles with a versioned base path of `/api/v1`. It manages three core resources:

| Resource | Path |
|---|---|
| Discovery | `GET /api/v1` |
| Rooms | `/api/v1/rooms` |
| Sensors | `/api/v1/sensors` |
| Sensor Readings | `/api/v1/sensors/{sensorId}/readings` |

All data is stored **in-memory** using `ConcurrentHashMap` and `ArrayList` — no database is used.

---

## Build & Run Instructions

### Prerequisites
- Java 11+
- Maven 3.6+

### Steps

```bash
# 1. Clone the repository
git clone https://github.com/YOUR_USERNAME/smart-campus-api.git
cd smart-campus-api

# 2. Build the fat JAR
mvn clean package

# 3. Run the server
java -jar target/smart-campus-api-1.0-SNAPSHOT.jar
```

The API will be available at: `http://localhost:8080/api/v1`

---

## Sample curl Commands

```bash
# 1. Discovery endpoint - get API metadata and resource links
curl -X GET http://localhost:8080/api/v1

# 2. List all rooms
curl -X GET http://localhost:8080/api/v1/rooms

# 3. Create a new room
curl -X POST http://localhost:8080/api/v1/rooms \
  -H "Content-Type: application/json" \
  -d '{"id":"HALL-01","name":"Main Hall","capacity":200}'

# 4. Get a specific room by ID
curl -X GET http://localhost:8080/api/v1/rooms/LIB-301

# 5. Delete a room (fails with 409 if it has sensors)
curl -X DELETE http://localhost:8080/api/v1/rooms/HALL-01

# 6. List all sensors
curl -X GET http://localhost:8080/api/v1/sensors

# 7. Filter sensors by type
curl -X GET "http://localhost:8080/api/v1/sensors?type=CO2"

# 8. Register a new sensor (linked to existing room)
curl -X POST http://localhost:8080/api/v1/sensors \
  -H "Content-Type: application/json" \
  -d '{"id":"OCC-001","type":"Occupancy","status":"ACTIVE","currentValue":0,"roomId":"LIB-301"}'

# 9. Post a new sensor reading
curl -X POST http://localhost:8080/api/v1/sensors/TEMP-001/readings \
  -H "Content-Type: application/json" \
  -d '{"value":24.5}'

# 10. Get all readings for a sensor
curl -X GET http://localhost:8080/api/v1/sensors/TEMP-001/readings

# 11. Try to register a sensor with a non-existent roomId (should return 422)
curl -X POST http://localhost:8080/api/v1/sensors \
  -H "Content-Type: application/json" \
  -d '{"id":"BAD-001","type":"Temperature","status":"ACTIVE","currentValue":0,"roomId":"FAKE-999"}'
```

---

## Report: Answers to Coursework Questions

### Part 1 — Service Architecture & Setup

**Q: Explain the default lifecycle of a JAX-RS Resource class. Is a new instance created per request or treated as a singleton? How does this impact in-memory data management?**

By default, JAX-RS follows a **per-request lifecycle**: the runtime creates a fresh instance of each resource class for every incoming HTTP request, and discards it once the response is sent. This is the "prototype" scope, and it is the default behaviour specified by the JAX-RS specification.

This has a direct impact on in-memory state management. Because each request gets its own resource object, any instance fields on the resource class would be re-initialised on every call — meaning data stored as instance variables would be lost between requests. To maintain shared state across requests, a separate **singleton** must be used. In this project, `DataStore.getInstance()` provides a single static instance containing `ConcurrentHashMap` collections that survive the lifecycle of individual resource objects. `ConcurrentHashMap` is used (rather than a plain `HashMap`) to prevent race conditions when multiple requests read and write concurrently, ensuring thread-safe access without full synchronisation blocks.

---

**Q: Why is Hypermedia (HATEOAS) considered a hallmark of advanced RESTful design? How does it benefit client developers?**

HATEOAS (Hypermedia As The Engine Of Application State) is the principle that API responses should include links describing what actions are available next, rather than forcing clients to have prior knowledge of the URL structure. It is considered the highest maturity level (Level 3) in Richardson's REST Maturity Model.

The benefit to client developers is significant: clients do not need to hard-code URLs or consult external documentation to navigate the API. Instead, they follow links embedded in responses — similar to how a web browser navigates HTML pages via anchor tags. This decouples the client from the server's URL structure, meaning the server can change its paths without breaking clients that follow links dynamically. It also makes the API self-documenting and easier to explore interactively.

---

### Part 2 — Room Management

**Q: When returning a list of rooms, what are the implications of returning only IDs versus returning full room objects?**

Returning **only IDs** reduces response payload size, which improves network efficiency — particularly useful when there are thousands of rooms or when clients are on slow connections. However, it forces clients to make additional requests (one per room) to retrieve details, increasing latency and the total number of round trips (the "N+1 problem").

Returning **full room objects** gives clients everything they need in one response, reducing round trips and simplifying client-side logic. The trade-off is a larger payload. In practice, the best approach depends on use case: list views typically return summary objects (id + name), while detail endpoints return the full object. This project returns full objects from `GET /rooms` to keep the client interaction simple, which is acceptable given the in-memory scale.

---

**Q: Is the DELETE operation idempotent in your implementation? Justify with what happens on repeated calls.**

Yes, DELETE is **idempotent** in this implementation, consistent with the HTTP specification. Idempotency means that making the same request multiple times produces the same server state as making it once.

- **First DELETE** on an existing, empty room: the room is removed and `204 No Content` is returned.
- **Second DELETE** on the same room ID: the room no longer exists, so the service returns `404 Not Found`.

The server state after both calls is identical — the room is gone. The response code differs (204 vs 404), but the *resource state* does not change after the first successful deletion. This is considered correct idempotent behaviour. If the room has sensors, a `409 Conflict` is returned on every attempt until sensors are removed, which is also consistent and idempotent.

---

### Part 3 — Sensor Operations & Filtering

**Q: Explain the technical consequences if a client sends data in a format other than `application/json` to a method annotated with `@Consumes(MediaType.APPLICATION_JSON)`.**

The `@Consumes` annotation declares which media types a resource method can accept. If a client sends a request with a `Content-Type` header of `text/plain` or `application/xml`, the JAX-RS runtime compares that type against the declared `@Consumes` value before the method is even invoked.

Finding no match, the runtime automatically returns **`415 Unsupported Media Type`** without calling the resource method at all. The developer does not need to write any validation code for this — it is handled entirely by the framework. This protects the endpoint from malformed or unexpected data formats and ensures the deserialisation logic (Jackson in this case) only runs on content it can safely process.

---

**Q: Contrast `@QueryParam` for filtering with using the type in the URL path (e.g., `/sensors/type/CO2`). Why is the query parameter approach superior?**

Using a **path segment** for filtering (e.g., `/sensors/type/CO2`) is problematic because it implies that `type/CO2` is a distinct resource with its own identity — which it is not. Path segments are semantically intended to identify a specific resource, not to filter a collection.

**Query parameters** (`?type=CO2`) are the correct RESTful approach for filtering, searching, and sorting because:
1. They are **optional by nature** — the base resource (`/sensors`) works without them.
2. They are **composable** — multiple filters can be combined easily (e.g., `?type=CO2&status=ACTIVE`).
3. They do not pollute the resource hierarchy with pseudo-resources.
4. They are the industry-standard convention understood by caching layers, API gateways, and developer tooling.
5. The base collection URL (`/sensors`) remains stable and cacheable regardless of what filters are applied.

---

### Part 4 — Sub-Resources

**Q: Discuss the architectural benefits of the Sub-Resource Locator pattern.**

The Sub-Resource Locator pattern allows a parent resource class to delegate responsibility for a nested URL segment to a separate, dedicated class. In this project, `SensorResource` delegates `/sensors/{sensorId}/readings` to `SensorReadingResource`.

The key benefits are:

1. **Separation of concerns**: Each class has a single, clear responsibility. `SensorResource` handles sensor CRUD; `SensorReadingResource` handles reading history. This follows the Single Responsibility Principle.
2. **Reduced complexity**: A single monolithic resource class handling every nested path would grow unmanageable. Delegation keeps each class small and focused.
3. **Testability**: Each sub-resource class can be unit-tested in isolation without needing the full resource hierarchy.
4. **Reusability**: A sub-resource class could theoretically be reused by multiple parent resources.
5. **Readability**: The URL hierarchy in the code mirrors the physical hierarchy (`sensor → readings`), making the codebase easier to understand and navigate.

---

### Part 5 — Error Handling

**Q: Why is HTTP 422 more semantically accurate than 404 when a sensor references a non-existent roomId?**

`404 Not Found` means the **requested URL** does not identify any resource. It is a navigation error — the client asked for something that doesn't exist at that path.

`422 Unprocessable Entity` means the **request was syntactically valid** (correct JSON, correct Content-Type, correct URL) but the **semantic content** of the payload is invalid. The server understood the request perfectly but cannot act on it because a business rule was violated — in this case, the `roomId` field references a room that doesn't exist in the system.

Using 404 here would be misleading: the endpoint `/api/v1/sensors` clearly exists. The problem is inside the payload, not with the URL. `422` accurately communicates "I understood your request, but the data inside it refers to something that doesn't exist, so I cannot process it."

---

**Q: From a cybersecurity standpoint, explain the risks of exposing internal Java stack traces to external API consumers.**

Exposing raw stack traces to API consumers is a serious security vulnerability for several reasons:

1. **Technology fingerprinting**: Stack traces reveal the exact framework versions, package names, and class structure (e.g., `org.glassfish.jersey 2.41`, `com.smartcampus.resource.SensorResource`). Attackers can use this to look up known CVEs for those specific versions.
2. **Application structure disclosure**: Class names and method signatures expose the internal architecture, making it easier to craft targeted attacks.
3. **File path leakage**: Stack traces often include absolute file system paths (e.g., `/home/app/src/main/java/...`), revealing server directory structure useful for path traversal attacks.
4. **Logic exposure**: The sequence of method calls in a trace reveals business logic flow, which can be exploited to find edge cases or injection points.
5. **Database schema hints**: If an ORM or SQL exception propagates, table names and column names may be exposed.

The `GlobalExceptionMapper` in this project prevents all of this by catching every unhandled `Throwable`, logging the full details server-side (where only administrators can see them), and returning only a generic `500 Internal Server Error` message to the client.

---

**Q: Why is it better to use JAX-RS filters for cross-cutting concerns like logging rather than inserting Logger statements in every resource method?**

Inserting logging statements manually into every resource method violates the **Don't Repeat Yourself (DRY)** principle and creates several practical problems:

1. **Code duplication**: Every method needs the same boilerplate log lines. If the log format changes, every method must be updated.
2. **Risk of omission**: It is easy to forget to add logging to a new method, creating blind spots in observability.
3. **Separation of concerns**: Logging is a cross-cutting concern — it is not part of the business logic of managing rooms or sensors. Mixing it in pollutes the resource methods and reduces readability.

JAX-RS filters (`ContainerRequestFilter` / `ContainerResponseFilter`) solve this cleanly: they execute automatically for **every** request and response without any modification to resource classes. Adding a new endpoint automatically gets logging for free. This is the same principle behind AOP (Aspect-Oriented Programming) — concerns that cut across the entire application should be handled in one place, not scattered throughout the codebase.
