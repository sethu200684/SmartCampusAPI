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

All data is stored **in-memory** using `ConcurrentHashMap` and `ArrayList` no database is used.

---

## Build & Run Instructions

### Prerequisites
- Java 11+
- Maven 3.6+

### Steps

```bash
# 1. Clone the repository
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

Lifecycle: JAX-RS resources use a per-request lifecycle (prototype scope) by default. A new instance is created for every HTTP request and destroyed after the response.

Impact: Instance variables do not persist between calls.

Solution: I used a Singleton pattern via DataStore.getInstance() and thread-safe ConcurrentHashMap collections to ensure data persists across the entire application lifecycle.

---

**Q: Why is Hypermedia (HATEOAS) considered a hallmark of advanced RESTful design? How does it benefit client developers?**

Hallmark: It is the highest level of REST maturity (Level 3), where the API provides navigation links within responses.

Benefit: Decouples clients from hardcoded URLs. Developers can follow links dynamically (like a browser), making the API self-documenting and easier to update without breaking client code.

---

### Part 2 — Room Management

**Q: When returning a list of rooms, what are the implications of returning only IDs versus returning full room objects?**

IDs only: Minimizes payload size but creates the "N+1 problem," requiring extra round-trips to fetch details.

Full Objects: Increases payload size but improves performance by reducing latency and simplifying client-side logic. I chose full objects to keep the Smart Campus interaction efficient at scale.

---

**Q: Is the DELETE operation idempotent in your implementation? Justify with what happens on repeated calls.**

Yes. Idempotency means multiple identical requests result in the same server state.

Justification: The first DELETE removes the room (204). Subsequent calls return 404 because the resource is already gone. The final state (room deleted) remains unchanged. If the room has sensors, a `409 Conflict` is returned on every attempt until sensors are removed, which is also consistent and idempotent.

---

### Part 3 — Sensor Operations & Filtering

**Q: Explain the technical consequences if a client sends data in a format other than `application/json` to a method annotated with `@Consumes(MediaType.APPLICATION_JSON)`.**

The `@Consumes` annotation declares which media types a resource method can accept. If a client sends a request with a `Content-Type` header of `text/plain` or `application/xml`, the JAX-RS runtime compares that type against the declared `@Consumes` value before the method is even invoked.

Finding no match, the runtime automatically returns **`415 Unsupported Media Type`** without calling the resource method at all. The developer does not need to write any validation code for this — it is handled entirely by the framework. This protects the endpoint from malformed or unexpected data formats and ensures the deserialisation logic (Jackson in this case) only runs on content it can safely process.

---

**Q: Contrast `@QueryParam` for filtering with using the type in the URL path (e.g., `/sensors/type/CO2`). Why is the query parameter approach superior?**

Using a **path segment** for filtering (e.g., `/sensors/type/CO2`) is problematic because it implies that `type/CO2` is a distinct resource with its own identity — which it is not. Path segments are semantically intended to identify a specific resource, not to filter a collection.

Query Parameters (?type=CO2) is superior because:

1.Semantics: Paths identify resources; query params modify the view/filter.
2.Optionality: Filters are optional, whereas path segments are usually mandatory.
3.Composability: Multiple filters (type, status, etc.) can be combined easily.

---

### Part 4 — Sub-Resources

**Q: Discuss the architectural benefits of the Sub-Resource Locator pattern.**

The Sub-Resource Locator pattern allows a parent resource class to delegate responsibility for a nested URL segment to a separate, dedicated class. In this project, `SensorResource` delegates `/sensors/{sensorId}/readings` to `SensorReadingResource`.

The key benefits are:

1. **Separation of concerns**: Keeps the SensorResource focused on metadata while delegating reading history to SensorReadingResource.
2. **Reduced complexity**: Prevents "God Classes" by breaking the URL hierarchy into manageable, testable, and readable modules.
3. **Testability**: Each sub-resource class can be unit-tested in isolation without needing the full resource hierarchy.
4. **Reusability**: A sub-resource class could theoretically be reused by multiple parent resources.

---

### Part 5 — Error Handling

**Q: Why is HTTP 422 more semantically accurate than 404 when a sensor references a non-existent roomId?**

`404 Not Found` means the **requested URL** does not identify any resource. It is a navigation error the client asked for something that doesn't exist at that path.

`422 Unprocessable Entity`: Correctly signals that the request was syntactically perfect, but the data inside (the roomId) is logically invalid for the business rules.

---

**Q: From a cybersecurity standpoint, explain the risks of exposing internal Java stack traces to external API consumers.**

Exposing raw stack traces to API consumers is a serious security vulnerability for several reasons:

1. **Technology fingerprinting**: Stack traces reveal the exact framework versions, package names, and class structure (e.g., `org.glassfish.jersey 2.41`, `com.smartcampus.resource.SensorResource`). Attackers can use this to look up known CVEs for those specific versions.
2. **Application structure disclosure**: Class names and method signatures expose the internal architecture, making it easier to craft targeted attacks.
3. **File path leakage**: Stack traces often include absolute file system paths (e.g., `/home/app/src/main/java/...`), revealing server directory structure useful for path traversal attacks.
4. **Logic exposure**: The sequence of method calls in a trace reveals business logic flow, which can be exploited to find edge cases or injection points.
5. **Database schema hints**: If an ORM or SQL exception propagates, table names and column names may be exposed.

Solution: My GlobalExceptionMapper hides these details behind a generic 500 error.

---

**Q: Why is it better to use JAX-RS filters for cross-cutting concerns like logging rather than inserting Logger statements in every resource method?**

DRY (Don't Repeat Yourself): Avoids duplicating logger code in every method.

Cross-Cutting Concern: Keeps business logic clean by handling logging automatically for every request/response in a single, centralized class.
