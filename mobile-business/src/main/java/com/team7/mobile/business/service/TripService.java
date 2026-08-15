package com.team7.mobile.business.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.common.dto.ItineraryDTO;
import com.team7.mobile.common.dto.ItineraryItemDTO;
import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.common.dto.TripDetailDTO;
import com.team7.mobile.common.dto.TripRequest;
import com.team7.mobile.common.exception.BusinessException;
import com.team7.mobile.common.exception.ForbiddenException;
import com.team7.mobile.common.exception.ResourceNotFoundException;
import com.team7.mobile.data.entity.AgentConversation;
import com.team7.mobile.data.entity.Itinerary;
import com.team7.mobile.data.entity.ItineraryItem;
import com.team7.mobile.data.entity.Trip;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.AgentConversationRepository;
import com.team7.mobile.data.repository.ItineraryItemRepository;
import com.team7.mobile.data.repository.ItineraryRepository;
import com.team7.mobile.data.repository.TripRepository;
import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.business.agent.AgentOrchestrator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trip management — create, query, update, cancel trips, agent conversation.
 */
@Service
public class TripService {

    private final TripRepository tripRepository;
    private final ItineraryRepository itineraryRepository;
    private final ItineraryItemRepository itineraryItemRepository;
    private final AgentConversationRepository agentConversationRepository;
    private final CurrentUser currentUser;
    private final AgentOrchestrator agentOrchestrator;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;

    public TripService(TripRepository tripRepository,
                       ItineraryRepository itineraryRepository,
                       ItineraryItemRepository itineraryItemRepository,
                       AgentConversationRepository agentConversationRepository,
                       CurrentUser currentUser,
                       AgentOrchestrator agentOrchestrator,
                       BookingService bookingService,
                       ObjectMapper objectMapper) {
        this.tripRepository = tripRepository;
        this.itineraryRepository = itineraryRepository;
        this.itineraryItemRepository = itineraryItemRepository;
        this.agentConversationRepository = agentConversationRepository;
        this.currentUser = currentUser;
        this.agentOrchestrator = agentOrchestrator;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new trip (manual creation, without Agent planning).
     */
    public TripDTO createTrip(TripRequest request) {
        User user = currentUser.get();
        if (user == null) {
            throw new IllegalStateException("User not authenticated");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("endDate must be after startDate");
        }

        Trip trip = new Trip();
        trip.setUser(user);
        trip.setTitle(request.getTitle());
        trip.setDestination(request.getDestination());
        trip.setStartDate(request.getStartDate());
        trip.setEndDate(request.getEndDate());
        trip.setBudgetTotal(request.getBudgetTotal());
        trip.setStatus(Trip.TripStatus.DRAFT);

        trip = tripRepository.save(trip);
        return toDTO(trip);
    }

    /**
     * Get a single trip by id (owner only).
     */
    public TripDTO getTripById(Long tripId) {
        Trip trip = findOwnedTrip(tripId);
        return toDTO(trip);
    }

    /**
     * Throws if the current user doesn't own tripId - used by
     * AgentChatService.getTask() so that agent-chat/agent-modify task
     * results (which can contain another traveler's itinerary and, after a
     * booking, passenger name/email) aren't readable by any authenticated
     * user who happens to know or guess a taskId, not just the trip's owner.
     */
    public void assertTripOwnership(Long tripId) {
        findOwnedTrip(tripId);
    }

    /**
     * Get full trip detail including day-by-day itinerary (owner only).
     */
    public TripDetailDTO getTripDetail(Long tripId) {
        Trip trip = findOwnedTrip(tripId);
        List<Itinerary> dayItineraries = itineraryRepository.findByTripIdOrderByDayNumber(tripId);

        // One batch query for every day's items instead of one query per day
        // (findByItineraryId(id) called N times in a loop) - a 7-day trip
        // used to be 1+7 queries here instead of 1+1.
        List<Long> itineraryIds = dayItineraries.stream().map(Itinerary::getId).collect(Collectors.toList());
        Map<Long, List<ItineraryItem>> itemsByItineraryId = itineraryItemRepository.findByItineraryIdIn(itineraryIds)
                .stream().collect(Collectors.groupingBy(item -> item.getItinerary().getId()));

        List<ItineraryDTO> itineraries = dayItineraries.stream()
                .map(itinerary -> toItineraryDTO(itinerary, itemsByItineraryId.getOrDefault(itinerary.getId(), List.of())))
                .collect(Collectors.toList());
        return new TripDetailDTO(
                trip.getId(), trip.getTitle(), trip.getDestination(),
                trip.getStartDate(), trip.getEndDate(), trip.getBudgetTotal(),
                trip.getStatus().name(), itineraries
        );
    }

    /**
     * List all trips of the current user, newest first.
     */
    public List<TripDTO> getUserTrips() {
        Long userId = currentUser.getId();
        return tripRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Update trip details (owner only).
     */
    public TripDTO updateTrip(Long tripId, TripRequest request) {
        Trip trip = findOwnedTrip(tripId);
        if (request.getTitle() != null) trip.setTitle(request.getTitle());
        if (request.getDestination() != null) trip.setDestination(request.getDestination());
        if (request.getStartDate() != null) trip.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) trip.setEndDate(request.getEndDate());
        if (request.getBudgetTotal() != null) trip.setBudgetTotal(request.getBudgetTotal());
        trip = tripRepository.save(trip);
        return toDTO(trip);
    }

    /**
     * Cancel a trip (owner only). Sets status to CANCELLED.
     */
    public void cancelTrip(Long tripId) {
        Trip trip = findOwnedTrip(tripId);
        trip.setStatus(Trip.TripStatus.CANCELLED);
        tripRepository.save(trip);
    }

    /**
     * Agent conversation for a trip.
     * Flow: extract requirements → if missing fields, ask clarifying question;
     * otherwise generate itinerary and return it.
     * Stateful across calls: prior turns for this (user, trip) are loaded from
     * agent_conversations and sent along so a follow-up like "make it 3000
     * instead" resolves against what was said earlier, not evaluated in
     * isolation; both the user's message and the Agent's reply are then
     * appended to that same history.
     * Backlog #4 + #6 + #10 (conversation continuity).
     */
    // deleteByTripId() inside saveItinerary() is a modifying derived query
    // that needs a writable transaction to run (open-in-view is disabled) -
    // without this, it throws TransactionRequiredException. Wrapping the
    // whole method also means a saveItinerary() failure rolls back the
    // trip.setStatus/setAgentItineraryJson update above it instead of
    // leaving the trip row committed with no matching itinerary rows.
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> agentChat(Long tripId, String message) {
        return agentChat(tripId, message, null);
    }

    /** Same as agentChat(2-arg), with a stage listener for app-visible progress. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> agentChat(Long tripId, String message,
                                         java.util.function.Consumer<String> stageListener) {
        Trip trip = findOwnedTrip(tripId);  // auth check
        User user = currentUser.get();

        ConversationContext context = buildConversationContext(user, trip);
        saveConversationTurn(user, trip, AgentConversation.Role.USER, message);

        // Step 1: extract structured trip requirements from free text
        if (stageListener != null) stageListener.accept("understanding_request");
        Map<String, Object> extracted = agentOrchestrator.extractRequirements(
                message, context.recentHistory(), context.summary());

        List<String> missing = (List<String>) extracted.get("missingFields");
        if (missing != null && !missing.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "NEEDS_MORE_INFO");
            result.put("missingFields", missing);
            result.put("clarifyingQuestion", extracted.get("clarifyingQuestion"));
            saveConversationTurn(user, trip, AgentConversation.Role.ASSISTANT,
                    (String) extracted.get("clarifyingQuestion"));
            return result;
        }

        // Step 2: generate full itinerary
        Map<String, Object> tripData = new LinkedHashMap<>();
        tripData.put("originCity", extracted.get("originCity"));
        tripData.put("destination", extracted.get("destination"));
        tripData.put("startDate", extracted.get("startDate"));
        tripData.put("endDate", extracted.get("endDate"));
        tripData.put("budgetTotal", extracted.get("budgetTotal"));
        Object prefs = extracted.get("preferences");
        tripData.put("preferences", prefs != null ? prefs : List.of());

        Map<String, Object> itinerary = agentOrchestrator.generateItinerary(tripData, stageListener);

        // Agent-side validation failures (e.g. "start date is in the past")
        // come back as {"error": "..."} from the Python service. Fail the task
        // with that message so the traveler sees the real reason - persisting
        // it as a PLANNED trip would leave a silently empty itinerary.
        if (itinerary.get("error") instanceof String errorMsg) {
            throw new BusinessException("AGENT_REJECTED", errorMsg, 400);
        }

        // Step 3: persist extracted info + generated itinerary
        if (extracted.get("originCity") != null) trip.setOriginCity((String) extracted.get("originCity"));
        if (extracted.get("destination") != null) trip.setDestination((String) extracted.get("destination"));
        if (extracted.get("startDate") != null) trip.setStartDate(LocalDate.parse((String) extracted.get("startDate")));
        if (extracted.get("endDate") != null) trip.setEndDate(LocalDate.parse((String) extracted.get("endDate")));
        if (extracted.get("budgetTotal") != null) trip.setBudgetTotal(new BigDecimal(extracted.get("budgetTotal").toString()));
        trip.setStatus(Trip.TripStatus.PLANNED);
        trip.setAgentItineraryJson(writeJson(itinerary));
        tripRepository.save(trip);

        saveItinerary(trip, itinerary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ITINERARY_READY");
        result.put("itinerary", itinerary);
        saveConversationTurn(user, trip, AgentConversation.Role.ASSISTANT,
                "I've put together a full itinerary for your trip to " + extracted.get("destination") + ".");
        return result;
    }

    // Mirrors agent-ml-service's config.MAX_CONVERSATION_HISTORY default - the
    // Agent only ever uses the last N turns anyway, so there's no point
    // fetching/serializing/sending an unbounded conversation on every call.
    private static final int MAX_HISTORY_TURNS = 20;

    private record ConversationContext(List<Map<String, String>> recentHistory, String summary) {
    }

    /**
     * Builds what gets sent to the Agent for conversation continuity: the
     * most recent MAX_HISTORY_TURNS turns verbatim, plus a compressed
     * summary of anything older than that window - without the summary,
     * facts established many turns back (destination, budget, ...) would
     * simply be lost once they age out of the verbatim window instead of
     * being carried forward.
     * <p>
     * The summary is cached on the Trip and only recomputed when the window
     * has actually advanced since it was last computed (tracked via
     * conversationSummarizedThroughCount) - and even then, only the NEWLY
     * dropped turns are sent to be folded in, not the entire older history.
     */
    private ConversationContext buildConversationContext(User user, Trip trip) {
        long totalCount = agentConversationRepository.countByUserIdAndTripId(user.getId(), trip.getId());
        int windowStart = (int) Math.max(0, totalCount - MAX_HISTORY_TURNS);

        List<AgentConversation> recentTurns = agentConversationRepository.findByUserIdAndTripIdOrderByCreatedAtDesc(
                user.getId(), trip.getId(), org.springframework.data.domain.PageRequest.of(0, MAX_HISTORY_TURNS));
        java.util.Collections.reverse(recentTurns);
        List<Map<String, String>> recentHistory = toHistoryPayload(recentTurns);

        String summary = trip.getConversationSummary();
        int summarizedThrough = trip.getConversationSummarizedThroughCount() != null
                ? trip.getConversationSummarizedThroughCount() : 0;

        if (windowStart > summarizedThrough) {
            List<AgentConversation> newlyDroppedTurns = agentConversationRepository
                    .findByUserIdAndTripIdOrderByCreatedAtAsc(user.getId(), trip.getId(),
                            org.springframework.data.domain.PageRequest.of(0, windowStart))
                    .subList(summarizedThrough, windowStart);
            summary = agentOrchestrator.summarizeConversation(summary, toHistoryPayload(newlyDroppedTurns));
            trip.setConversationSummary(summary);
            trip.setConversationSummarizedThroughCount(windowStart);
            tripRepository.save(trip);
        }

        return new ConversationContext(recentHistory, summary);
    }

    private List<Map<String, String>> toHistoryPayload(List<AgentConversation> turns) {
        return turns.stream()
                .map(turn -> Map.of(
                        "role", turn.getRole() == AgentConversation.Role.ASSISTANT ? "assistant" : "user",
                        "content", turn.getContent()))
                .collect(Collectors.toList());
    }

    /** Records one turn of the agent-chat conversation. No-op if content is null (e.g. no clarifying question). */
    private void saveConversationTurn(User user, Trip trip, AgentConversation.Role role, String content) {
        if (content == null) return;
        AgentConversation turn = new AgentConversation();
        turn.setUser(user);
        turn.setTrip(trip);
        turn.setRole(role);
        turn.setContent(content);
        agentConversationRepository.save(turn);
    }

    /**
     * Books flight + hotel for an owned trip via the Agent, then persists
     * whichever of the flight/hotel legs succeeded into the bookings table
     * (via the already-ownership-checked BookingService.createBooking()) so
     * a completed Agent booking actually shows up on the trip afterwards.
     * <p>
     * Synchronous, like the direct /api/agent/book-trip passthrough this
     * calls through to - full async + conversation-history integration
     * (matching agentChat()'s pattern) is a separate, larger piece of work
     * not in scope here.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> bookTrip(Long tripId, Map<String, Object> bookingRequest) {
        findOwnedTrip(tripId);  // auth check

        Map<String, Object> result = agentOrchestrator.bookTrip(bookingRequest);
        persistBookingLeg(tripId, "FLIGHT", (Map<String, Object>) result.get("flightResult"));
        persistBookingLeg(tripId, "HOTEL", (Map<String, Object>) result.get("hotelResult"));
        return result;
    }

    /** Persists one leg (flight or hotel) of a book_full_trip result, if that leg actually succeeded. */
    private void persistBookingLeg(Long tripId, String type, Map<String, Object> legResult) {
        if (legResult == null || !Boolean.TRUE.equals(legResult.get("success"))) {
            return;
        }
        boolean isFlight = "FLIGHT".equals(type);
        // NOT Map.getOrDefault(...): liteapi_client.book_hotel() always sets
        // "hotelConfirmationCode" in its return dict (via data.get(...)), so
        // the key is always PRESENT in the JSON that reaches here - if
        // LiteAPI just hasn't issued a confirmation code yet (a real,
        // observed pattern for hotel bookings, not hypothetical), that's
        // "present mapped to null", which getOrDefault does NOT fall back
        // on. Losing bookingRef entirely on a successful hotel booking would
        // leave the traveler with a confirmed reservation and no reference
        // to look it up by, so this must fall back to bookingId either way.
        String bookingRef = isFlight
                ? (String) legResult.get("bookingReference")
                : (String) firstNonNull(legResult.get("hotelConfirmationCode"), legResult.get("bookingId"));
        Object amount = isFlight ? legResult.get("totalAmount") : legResult.get("totalPrice");
        BigDecimal price = amount != null ? new BigDecimal(amount.toString()) : null;
        // Duffel's flight booking result doesn't echo back a currency field
        // (unlike the hotel result) - both Duffel/LiteAPI test-mode content
        // used by this integration are USD-denominated, so USD is a correct
        // default here, not a guess.
        String currency = (String) legResult.getOrDefault("currency", "USD");

        bookingService.createBooking(tripId, type, bookingRef, price, currency);
    }

    /**
     * Modifies the trip's existing itinerary via a natural-language change
     * request (e.g. "find me a cheaper hotel instead"). Requires an
     * itinerary to already exist (produced by agentChat()) - modify_itinerary
     * needs the FULL raw itinerary JSON as its starting point (totalCost,
     * warnings, maxHotelCommuteMinutes, etc.), which the flattened Itinerary/
     * ItineraryItem rows alone can't reconstruct - see Trip.agentItineraryJson.
     * Same conversation-history + persistence pattern as agentChat().
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> modifyItinerary(Long tripId, String userRequest) {
        return modifyItinerary(tripId, userRequest, null);
    }

    /** Same as modifyItinerary(2-arg), with a stage listener for app-visible progress.
     * Needs its own @Transactional: AgentChatService calls THIS overload through
     * the Spring proxy, so the annotation on the 2-arg sibling never applies -
     * without it, saveItinerary's deleteByItineraryId throws "No EntityManager
     * with actual transaction available" (observed live on the first real
     * agent-modify call from the app). */
    @Transactional
    public Map<String, Object> modifyItinerary(Long tripId, String userRequest,
                                               java.util.function.Consumer<String> stageListener) {
        Trip trip = findOwnedTrip(tripId);
        User user = currentUser.get();

        String existingJson = trip.getAgentItineraryJson();
        if (existingJson == null) {
            throw new com.team7.mobile.common.exception.BusinessException("NO_ITINERARY",
                    "This trip doesn't have an itinerary yet - generate one first via agent-chat.", 400);
        }
        Map<String, Object> currentItinerary = readJsonMap(existingJson);

        saveConversationTurn(user, trip, AgentConversation.Role.USER, userRequest);

        Map<String, Object> updatedItinerary = agentOrchestrator.modifyItinerary(currentItinerary, userRequest, stageListener);

        // Same guard as agentChat(): a Python-side {"error": ...} result must
        // fail the task visibly, not be persisted as a valid itinerary.
        if (updatedItinerary.get("error") instanceof String errorMsg) {
            throw new BusinessException("AGENT_REJECTED", errorMsg, 400);
        }

        // orchestrator.py's _validate_and_normalize_itinerary now requires
        // changeApplied to be a real boolean whenever existing_itinerary is
        // passed (i.e. always, on this modify path) - a missing/malformed
        // value fails validation and retries there instead of ever reaching
        // this line. Still treating anything other than an explicit false as
        // "something changed" costs nothing and protects against an older
        // agent-ml-service build that predates that validation.
        boolean changeApplied = !Boolean.FALSE.equals(updatedItinerary.get("changeApplied"));

        // changeApplied is a one-off result flag describing THIS call, not
        // itinerary data - persist a copy with it stripped out, so the next
        // modifyItinerary() call doesn't read it back via currentItinerary
        // and feed it into the LLM prompt as if it were a real field of the
        // trip's itinerary (updatedItinerary itself, still carrying the
        // flag, is kept below for the immediate API response).
        Map<String, Object> persistedItinerary = new LinkedHashMap<>(updatedItinerary);
        persistedItinerary.remove("changeApplied");

        trip.setAgentItineraryJson(writeJson(persistedItinerary));
        tripRepository.save(trip);
        saveItinerary(trip, persistedItinerary);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", changeApplied ? "ITINERARY_UPDATED" : "NO_CHANGE_FOUND");
        result.put("itinerary", updatedItinerary);

        // The Agent's own warnings note is already a complete, accurate
        // description of the outcome - including the case where nothing
        // could actually be changed (e.g. "No more expensive hotel was
        // found ... remains unchanged"). Stapling "I've updated the
        // itinerary: " in front of that used to produce a self-contradictory
        // message (claiming an update happened right before explaining that
        // it didn't) - verified live via a real modify-itinerary call that
        // found no cheaper/pricier alternative existed.
        List<String> warnings = (List<String>) updatedItinerary.get("warnings");
        String summary = (warnings != null && !warnings.isEmpty())
                ? warnings.get(warnings.size() - 1)
                : (changeApplied
                        ? "I've updated the itinerary as requested."
                        : "No changes were needed - your itinerary already met the request.");
        saveConversationTurn(user, trip, AgentConversation.Role.ASSISTANT, summary);

        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize itinerary", e);
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new com.team7.mobile.common.exception.BusinessException("CORRUPT_ITINERARY",
                    "Stored itinerary could not be parsed - cannot apply changes.", 500);
        }
    }

    /**
     * Parse Agent itinerary JSON ({day1: {date, flight, hotel, breakfast, lunch, dinner, attraction}, ...})
     * and persist as Itinerary + ItineraryItem rows. Replaces any previous plan.
     */
    @SuppressWarnings("unchecked")
    private void saveItinerary(Trip trip, Map<String, Object> agentItinerary) {
        // Replace previous plan. ItineraryItem -> Itinerary is a plain
        // @ManyToOne with no inverse @OneToMany/cascade on Itinerary, so
        // deleting itineraries first would violate the fk_items_itinerary
        // foreign key whenever this trip already has a prior itinerary with
        // items - children must go first.
        for (Itinerary existing : itineraryRepository.findByTripIdOrderByDayNumber(trip.getId())) {
            itineraryItemRepository.deleteByItineraryId(existing.getId());
        }
        itineraryRepository.deleteByTripId(trip.getId());

        int dayNumber = 1;
        while (true) {
            Object dayObj = agentItinerary.get("day" + dayNumber);
            if (!(dayObj instanceof Map)) break;

            Map<String, Object> day = (Map<String, Object>) dayObj;
            Itinerary itinerary = new Itinerary();
            itinerary.setTrip(trip);
            itinerary.setDayNumber(dayNumber);
            // NOT day.getOrDefault("date", trip.getStartDate().toString()) - Java
            // evaluates a getOrDefault's default argument eagerly regardless of
            // whether the key is present, so that would NPE whenever
            // trip.getStartDate() is null even though "date" is always set here.
            String dateStr = (String) day.get("date");
            LocalDate dayDate = LocalDate.parse(dateStr != null ? dateStr : trip.getStartDate().toString());
            itinerary.setDate(dayDate);
            itinerary.setGeneratedByAgent(true);
            itinerary = itineraryRepository.save(itinerary);

            // Pass dayDate explicitly instead of reading itinerary.getDate()
            // inside saveItem - a mocked repository's save() returns null in
            // tests, and calling null.getDate() there caused NPEs. The date
            // is a plain value here regardless of what save() returns.
            saveItem(itinerary, "FLIGHT", day.get("flight"), dayDate);
            saveItem(itinerary, "HOTEL", day.get("hotel"), dayDate);
            saveItem(itinerary, "RESTAURANT", day.get("breakfast"), dayDate);
            saveItem(itinerary, "RESTAURANT", day.get("lunch"), dayDate);
            saveItem(itinerary, "RESTAURANT", day.get("dinner"), dayDate);
            saveItem(itinerary, "ATTRACTION", day.get("attraction"), dayDate);

            dayNumber++;
        }
    }

    /** Persist one activity object (or skip if null) — keeps raw JSON in description. */
    @SuppressWarnings("unchecked")
    private void saveItem(Itinerary itinerary, String type, Object activityObj, LocalDate dayDate) {
        if (!(activityObj instanceof Map)) return;
        Map<String, Object> activity = (Map<String, Object>) activityObj;

        ItineraryItem item = new ItineraryItem();
        item.setItinerary(itinerary);
        item.setType(ItineraryItem.ItemType.valueOf(type));
        item.setTitle(extractTitle(activity));
        // Flight activities never carry a "startTime"/"endTime" key - Duffel's
        // fields (preserved verbatim per the assembly prompt) are named
        // "departureTime"/"arrivalTime" instead (verified against a real
        // generated itinerary). Without this fallback, every flight's
        // startTime/endTime silently persisted as null. Hotel/restaurant/
        // attraction activities have none of these four keys at all - this
        // remains a no-op (null) for them, same as before.
        // NOT Map.getOrDefault(key, ...) - getOrDefault only falls back when
        // the key is entirely ABSENT, not when it's present mapped to null
        // (Jackson deserializes a JSON "startTime": null into exactly that:
        // the key present, value null). firstNonNull's plain .get() + null
        // check treats "absent" and "present-but-null" the same way, so the
        // fallback still kicks in either way.
        LocalDateTime start = parseActivityTime(firstNonNull(activity.get("startTime"), activity.get("departureTime")), dayDate);
        LocalDateTime end = parseActivityTime(firstNonNull(activity.get("endTime"), activity.get("arrivalTime")), dayDate);
        // Cross-midnight handling: a bare "HH:MM" end time earlier than the
        // start time (e.g. hotel check-in 14:00 / check-out 12:00, or a return
        // flight departing 20:50 and landing 10:40) means the end belongs to
        // the NEXT day. The Agent only supplies clock times without dates, so
        // roll the date forward here instead of showing an impossible range.
        if (start != null && end != null && !end.isAfter(start)) {
            end = end.plusDays(1);
        }
        item.setStartTime(start);
        item.setEndTime(end);
        // Agent activities never carry a "location" key - hotel/restaurant/
        // attraction data uses "address" instead (verified against a real
        // generated itinerary); flight has no address-equivalent single
        // field (only separate "origin"/"destination" IATA codes), so this
        // stays null for flights rather than fabricating a value that was
        // never actually in the source data.
        item.setLocation(str(firstNonNull(activity.get("location"), activity.get("address"))));
        item.setBookingRef(str(activity.get("bookingRef")));
        item.setPrice(parsePrice(activity));
        // Agent flight/hotel/restaurant activities never carry a "currency"
        // key (verified against a real generated itinerary) - Duffel/LiteAPI
        // test-mode content is USD-denominated, matching the same default
        // already used in persistBookingLeg() below.
        item.setCurrency(str(activity.getOrDefault("currency", "USD")));
        // Keep the full raw activity JSON so no Agent data is lost
        try {
            item.setDescription(objectMapper.writeValueAsString(activity));
        } catch (Exception e) {
            item.setDescription(String.valueOf(activity));
        }
        itineraryItemRepository.save(item);
    }

    /** Pick a display title from common fields. */
    private String extractTitle(Map<String, Object> activity) {
        for (String key : new String[]{"title", "name", "hotelName", "airline", "restaurantName", "attractionName", "flightNumber"}) {
            if (activity.get(key) != null) return String.valueOf(activity.get(key));
        }
        return "";
    }

    private String str(Object v) { return v != null ? String.valueOf(v) : null; }

    private Object firstNonNull(Object primary, Object fallback) { return primary != null ? primary : fallback; }

    /**
     * Parses an activity time. Accepts either a full ISO datetime or a bare
     * "HH:MM" (which the Agent's assembly prompt now mandates for every
     * activity) - the bare form is combined with the day's date. Returns null
     * when the value is missing/unparseable rather than throwing.
     */
    private LocalDateTime parseActivityTime(Object v, LocalDate dayDate) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            try {
                return LocalDateTime.of(dayDate != null ? dayDate : LocalDate.now(), LocalTime.parse(s));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private LocalDateTime parseDateTime(Object v) {
        if (v == null) return null;
        try { return LocalDateTime.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private BigDecimal parsePrice(Map<String, Object> activity) {
        // "pricePerNight" is hotel's actual field name (verified against a
        // real generated itinerary) - without it, none of "price"/
        // "totalPrice"/"amount" ever matches a hotel activity, so every
        // hotel ItineraryItem silently persisted with price=null even though
        // real per-night pricing was right there in the source data. Each
        // day's hotel ItineraryItem represents one night of the stay, so
        // "pricePerNight" is the semantically correct value to use as-is.
        for (String key : new String[]{"price", "totalPrice", "amount", "pricePerNight"}) {
            Object v = activity.get(key);
            if (v != null) {
                try { return new BigDecimal(v.toString()); } catch (Exception e) { /* ignore */ }
            }
        }
        return null;
    }

    private ItineraryDTO toItineraryDTO(Itinerary itinerary, List<ItineraryItem> itineraryItems) {
        // Chronological order for the day. Items with no start time fall
        // back to their end time (e.g. check-out hotels: 12:00 must sort
        // before the return flight) - only fully untimed items go last.
        List<ItineraryItemDTO> items = itineraryItems.stream()
                .sorted(java.util.Comparator.comparing(
                        (ItineraryItem item) -> item.getStartTime() != null
                                ? item.getStartTime()
                                : item.getEndTime(),
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(this::toItemDTO)
                .collect(Collectors.toList());
        return new ItineraryDTO(itinerary.getId(), itinerary.getDayNumber(), itinerary.getDate(),
                itinerary.getGeneratedByAgent(), items);
    }

    private ItineraryItemDTO toItemDTO(ItineraryItem item) {
        return new ItineraryItemDTO(
                item.getId(), item.getType().name(),
                item.getStartTime(), item.getEndTime(),
                item.getTitle(), item.getDescription(), item.getLocation(),
                item.getBookingRef(), item.getPrice(), item.getCurrency()
        );
    }

    private Trip findOwnedTrip(Long tripId) {
        Long userId = currentUser.getId();
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
        if (!trip.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Not authorized to access trip: " + tripId);
        }
        return trip;
    }

    private TripDTO toDTO(Trip trip) {
        return new TripDTO(
                trip.getId(),
                trip.getUser().getId(),
                trip.getTitle(),
                trip.getOriginCity(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getBudgetTotal(),
                trip.getStatus().name(),
                trip.getCreatedAt()
        );
    }

    /**
     * All trips across the company (approval sync / budget review).
     * Requires MANAGER / FINANCE / ADMIN role.
     */
    public List<TripDTO> getAllTrips() {
        requireApprovalRole();
        return tripRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Throws if the current principal is not an approver (MANAGER/FINANCE/ADMIN).
     * Stateless: reads the role from the JWT authorities in the SecurityContext,
     * NOT from the DB — so tokens issued by the Web group (whose users are not
     * in our user table) still work for cross-system approval calls.
     */
    private void requireApprovalRole() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("User not authenticated");
        }
        boolean allowed = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER")
                        || a.getAuthority().equals("ROLE_FINANCE")
                        || a.getAuthority().equals("ROLE_ADMIN"));
        if (!allowed) {
            throw new com.team7.mobile.common.exception.ForbiddenException(
                    "Only MANAGER/FINANCE/ADMIN can access company-wide data");
        }
    }
}
