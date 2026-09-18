-- =============================================================================
-- V6 — how many times we have asked this partner to rephrase.
--
-- The clarification loop's entire state, and it lives on the session rather than
-- in a process variable for a reason worth writing down: at the ENTRY point there
-- is no process. No concern has been chosen, which is the whole situation the loop
-- exists to resolve. The Engineering Design puts the loop in BPMN, and for the
-- IN-CONCERN case that is still right — this column is only for the entry path.
--
-- NOT NULL DEFAULT 0 so every existing row is "never asked", which is true of all
-- of them: nothing before this migration could ask.
--
-- It is also a number worth reporting on. "How often does a partner have to
-- rephrase before we place them" is a direct measure of how well the taxonomy
-- matches how partners actually describe their problems, and it is the one signal
-- that would justify adding a concern to the catalogue.
-- =============================================================================

ALTER TABLE help_session
    ADD COLUMN clarification_attempts INT NOT NULL DEFAULT 0;
