--
-- PostgreSQL database dump
--

-- Dumped from database version 12.3
-- Dumped by pg_dump version 12.3

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: admins; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.admins (
    username text NOT NULL,
    password text NOT NULL,
    spectator text NOT NULL,
    administrator text NOT NULL,
    locations text NOT NULL,
    unrestricted_view text NOT NULL
);


ALTER TABLE public.admins OWNER TO contest;

--
-- Name: areas; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.areas (
    id integer NOT NULL,
    name text NOT NULL,
    printer text NOT NULL
);


ALTER TABLE public.areas OWNER TO contest;

--
-- Name: areas_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.areas_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.areas_id_seq OWNER TO contest;

--
-- Name: areas_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.areas_id_seq OWNED BY public.areas.id;


--
-- Name: clarification_requests; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.clarification_requests (
    id bigint NOT NULL,
    contest bigint NOT NULL,
    team bigint NOT NULL,
    problem text NOT NULL,
    request text NOT NULL,
    arrived timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    answered boolean NOT NULL,
    answer text DEFAULT ''::text NOT NULL
);


ALTER TABLE public.clarification_requests OWNER TO contest;

--
-- Name: clarification_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.clarification_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.clarification_requests_id_seq OWNER TO contest;

--
-- Name: clarification_requests_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.clarification_requests_id_seq OWNED BY public.clarification_requests.id;


--
-- Name: clarifications; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.clarifications (
    id bigint NOT NULL,
    contest bigint NOT NULL,
    problem text NOT NULL,
    text text NOT NULL,
    posted timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    hidden boolean NOT NULL
);


ALTER TABLE public.clarifications OWNER TO contest;

--
-- Name: clarifications_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.clarifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.clarifications_id_seq OWNER TO contest;

--
-- Name: clarifications_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.clarifications_id_seq OWNED BY public.clarifications.id;


--
-- Name: clarifications_seen; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.clarifications_seen (
    contest bigint NOT NULL,
    team bigint NOT NULL,
    max_seen timestamp without time zone NOT NULL
);


ALTER TABLE public.clarifications_seen OWNER TO contest;

--
-- Name: computer_locations; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.computer_locations (
    location bigint NOT NULL,
    name text NOT NULL,
    id inet NOT NULL
);


ALTER TABLE public.computer_locations OWNER TO contest;

--
-- Name: contests; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.contests (
    id integer NOT NULL,
    start_time timestamp with time zone NOT NULL,
    freeze_time timestamp with time zone,
    end_time timestamp with time zone NOT NULL,
    expose_time timestamp with time zone NOT NULL,
    polygon_id text NOT NULL,
    language text DEFAULT 'english'::text NOT NULL,
    name text NOT NULL,
    school_mode boolean DEFAULT false NOT NULL
);


ALTER TABLE public.contests OWNER TO contest;

--
-- Name: contests_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.contests_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.contests_id_seq OWNER TO contest;

--
-- Name: contests_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.contests_id_seq OWNED BY public.contests.id;


--
-- Name: custom_test; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.custom_test (
    id bigint NOT NULL,
    contest bigint NOT NULL,
    team_id bigint NOT NULL,
    tested boolean DEFAULT false NOT NULL,
    language_id bigint NOT NULL,
    source bytea NOT NULL,
    input bytea NOT NULL,
    output bytea DEFAULT '\x'::bytea NOT NULL,
    time_ms bigint DEFAULT 0 NOT NULL,
    memory_bytes bigint DEFAULT 0 NOT NULL,
    return_code bigint DEFAULT 0 NOT NULL,
    result_code integer DEFAULT 0 NOT NULL,
    submit_time_absolute timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finish_time timestamp without time zone
);


ALTER TABLE public.custom_test OWNER TO contest;

--
-- Name: custom_test_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.custom_test_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.custom_test_id_seq OWNER TO contest;

--
-- Name: custom_test_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.custom_test_id_seq OWNED BY public.custom_test.id;


--
-- Name: extra_info; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.extra_info (
    contest bigint NOT NULL,
    num bigint NOT NULL,
    heading text NOT NULL,
    data text NOT NULL
);


ALTER TABLE public.extra_info OWNER TO contest;

--
-- Name: languages; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.languages (
    id integer NOT NULL,
    name text NOT NULL,
    module_id text NOT NULL
);


ALTER TABLE public.languages OWNER TO contest;

--
-- Name: languages_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.languages_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.languages_id_seq OWNER TO contest;

--
-- Name: languages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.languages_id_seq OWNED BY public.languages.id;


--
-- Name: logins; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.logins (
    contest bigint NOT NULL,
    team bigint NOT NULL,
    username text NOT NULL,
    password text NOT NULL
);


ALTER TABLE public.logins OWNER TO contest;

--
-- Name: messages; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.messages (
    id bigint NOT NULL,
    contest bigint NOT NULL,
    team bigint NOT NULL,
    kind text NOT NULL,
    value text NOT NULL,
    seen boolean NOT NULL
);


ALTER TABLE public.messages OWNER TO contest;

--
-- Name: messages_contest_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.messages_contest_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.messages_contest_seq OWNER TO contest;

--
-- Name: messages_contest_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.messages_contest_seq OWNED BY public.messages.contest;


--
-- Name: messages_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.messages_id_seq OWNER TO contest;

--
-- Name: messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.messages_id_seq OWNED BY public.messages.id;


--
-- Name: messages_team_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.messages_team_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.messages_team_seq OWNER TO contest;

--
-- Name: messages_team_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.messages_team_seq OWNED BY public.messages.team;


--
-- Name: participants; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.participants (
    contest bigint NOT NULL,
    team bigint NOT NULL,
    disabled boolean DEFAULT false NOT NULL,
    not_rated boolean DEFAULT false NOT NULL,
    no_print boolean DEFAULT false NOT NULL
);


ALTER TABLE public.participants OWNER TO contest;

--
-- Name: print_jobs; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.print_jobs (
    id bigint NOT NULL,
    contest bigint NOT NULL,
    team bigint NOT NULL,
    filename text NOT NULL,
    data bytea NOT NULL,
    arrived timestamp without time zone NOT NULL,
    printed timestamp without time zone,
    error text NOT NULL,
    pages bigint NOT NULL,
    computer_id inet NOT NULL
);


ALTER TABLE public.print_jobs OWNER TO contest;

--
-- Name: print_jobs_contest_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.print_jobs_contest_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.print_jobs_contest_seq OWNER TO contest;

--
-- Name: print_jobs_contest_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.print_jobs_contest_seq OWNED BY public.print_jobs.contest;


--
-- Name: print_jobs_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.print_jobs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.print_jobs_id_seq OWNER TO contest;

--
-- Name: print_jobs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.print_jobs_id_seq OWNED BY public.print_jobs.id;


--
-- Name: print_jobs_team_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.print_jobs_team_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.print_jobs_team_seq OWNER TO contest;

--
-- Name: print_jobs_team_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.print_jobs_team_seq OWNED BY public.print_jobs.team;


--
-- Name: problems; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.problems (
    contest_id bigint NOT NULL,
    id text NOT NULL,
    tests bigint NOT NULL,
    name text NOT NULL
);


ALTER TABLE public.problems OWNER TO contest;

--
-- Name: results; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.results (
    testing_id bigint NOT NULL,
    test_id bigint NOT NULL,
    result_code smallint NOT NULL,
    record_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    time_ms bigint NOT NULL,
    memory_bytes bigint NOT NULL,
    return_code integer DEFAULT 0 NOT NULL,
    tester_return_code integer DEFAULT 0 NOT NULL,
    tester_output bytea NOT NULL,
    tester_error bytea NOT NULL
);


ALTER TABLE public.results OWNER TO contest;

--
-- Name: schools; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.schools (
    id integer NOT NULL,
    short_name text NOT NULL,
    full_name text DEFAULT ''::text NOT NULL
);


ALTER TABLE public.schools OWNER TO contest;

--
-- Name: schools_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.schools_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.schools_id_seq OWNER TO contest;

--
-- Name: schools_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.schools_id_seq OWNED BY public.schools.id;


--
-- Name: submits; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.submits (
    contest bigint NOT NULL,
    team_id bigint NOT NULL,
    problem text NOT NULL,
    id bigint NOT NULL,
    language_id bigint NOT NULL,
    tested boolean DEFAULT false NOT NULL,
    success boolean DEFAULT false NOT NULL,
    passed bigint DEFAULT 0 NOT NULL,
    source bytea NOT NULL,
    testing_id bigint DEFAULT 0 NOT NULL,
    submit_time_absolute timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    taken integer DEFAULT 0 NOT NULL,
    compiled boolean DEFAULT false NOT NULL,
    computer_id inet
);


ALTER TABLE public.submits OWNER TO contest;

--
-- Name: submits_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.submits_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.submits_id_seq OWNER TO contest;

--
-- Name: submits_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.submits_id_seq OWNED BY public.submits.id;


--
-- Name: teams; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.teams (
    id integer NOT NULL,
    school bigint NOT NULL,
    num bigint NOT NULL,
    name text NOT NULL
);


ALTER TABLE public.teams OWNER TO contest;

--
-- Name: teams_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.teams_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.teams_id_seq OWNER TO contest;

--
-- Name: teams_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.teams_id_seq OWNED BY public.teams.id;


--
-- Name: testings; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.testings (
    id bigint NOT NULL,
    submit bigint NOT NULL,
    start_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    problem_id text NOT NULL,
    finish_time timestamp without time zone
);


ALTER TABLE public.testings OWNER TO contest;

--
-- Name: testings_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.testings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.testings_id_seq OWNER TO contest;

--
-- Name: testings_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.testings_id_seq OWNED BY public.testings.id;


--
-- Name: waiter_task_record; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.waiter_task_record (
    id bigint NOT NULL,
    room text NOT NULL,
    ts timestamp without time zone NOT NULL
);


ALTER TABLE public.waiter_task_record OWNER TO contest;

--
-- Name: waiter_tasks; Type: TABLE; Schema: public; Owner: contest
--

CREATE TABLE public.waiter_tasks (
    id bigint NOT NULL,
    created timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    message text NOT NULL,
    rooms text NOT NULL
);


ALTER TABLE public.waiter_tasks OWNER TO contest;

--
-- Name: waiter_tasks_id_seq; Type: SEQUENCE; Schema: public; Owner: contest
--

CREATE SEQUENCE public.waiter_tasks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.waiter_tasks_id_seq OWNER TO contest;

--
-- Name: waiter_tasks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: contest
--

ALTER SEQUENCE public.waiter_tasks_id_seq OWNED BY public.waiter_tasks.id;


--
-- Name: areas id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.areas ALTER COLUMN id SET DEFAULT nextval('public.areas_id_seq'::regclass);


--
-- Name: clarification_requests id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.clarification_requests ALTER COLUMN id SET DEFAULT nextval('public.clarification_requests_id_seq'::regclass);


--
-- Name: clarifications id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.clarifications ALTER COLUMN id SET DEFAULT nextval('public.clarifications_id_seq'::regclass);


--
-- Name: contests id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.contests ALTER COLUMN id SET DEFAULT nextval('public.contests_id_seq'::regclass);


--
-- Name: custom_test id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.custom_test ALTER COLUMN id SET DEFAULT nextval('public.custom_test_id_seq'::regclass);


--
-- Name: languages id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.languages ALTER COLUMN id SET DEFAULT nextval('public.languages_id_seq'::regclass);


--
-- Name: messages id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.messages ALTER COLUMN id SET DEFAULT nextval('public.messages_id_seq'::regclass);


--
-- Name: messages contest; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.messages ALTER COLUMN contest SET DEFAULT nextval('public.messages_contest_seq'::regclass);


--
-- Name: messages team; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.messages ALTER COLUMN team SET DEFAULT nextval('public.messages_team_seq'::regclass);


--
-- Name: print_jobs id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.print_jobs ALTER COLUMN id SET DEFAULT nextval('public.print_jobs_id_seq'::regclass);


--
-- Name: print_jobs contest; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.print_jobs ALTER COLUMN contest SET DEFAULT nextval('public.print_jobs_contest_seq'::regclass);


--
-- Name: print_jobs team; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.print_jobs ALTER COLUMN team SET DEFAULT nextval('public.print_jobs_team_seq'::regclass);


--
-- Name: schools id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.schools ALTER COLUMN id SET DEFAULT nextval('public.schools_id_seq'::regclass);


--
-- Name: submits id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.submits ALTER COLUMN id SET DEFAULT nextval('public.submits_id_seq'::regclass);


--
-- Name: teams id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.teams ALTER COLUMN id SET DEFAULT nextval('public.teams_id_seq'::regclass);


--
-- Name: testings id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.testings ALTER COLUMN id SET DEFAULT nextval('public.testings_id_seq'::regclass);


--
-- Name: waiter_tasks id; Type: DEFAULT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.waiter_tasks ALTER COLUMN id SET DEFAULT nextval('public.waiter_tasks_id_seq'::regclass);


--
-- Name: admins admins_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.admins
    ADD CONSTRAINT admins_pkey PRIMARY KEY (username);


--
-- Name: areas areas_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.areas
    ADD CONSTRAINT areas_pkey PRIMARY KEY (id);


--
-- Name: clarification_requests clarification_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.clarification_requests
    ADD CONSTRAINT clarification_requests_pkey PRIMARY KEY (id);


--
-- Name: clarifications clarifications_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.clarifications
    ADD CONSTRAINT clarifications_pkey PRIMARY KEY (id);


--
-- Name: clarifications_seen clarifications_seen_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.clarifications_seen
    ADD CONSTRAINT clarifications_seen_pkey PRIMARY KEY (contest, team);


--
-- Name: computer_locations computer_locations_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.computer_locations
    ADD CONSTRAINT computer_locations_pkey PRIMARY KEY (id);


--
-- Name: contests contests_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.contests
    ADD CONSTRAINT contests_pkey PRIMARY KEY (id);


--
-- Name: custom_test custom_test_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.custom_test
    ADD CONSTRAINT custom_test_pkey PRIMARY KEY (id);


--
-- Name: extra_info extra_info_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.extra_info
    ADD CONSTRAINT extra_info_pkey PRIMARY KEY (contest, num);


--
-- Name: languages languages_module_id_key; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.languages
    ADD CONSTRAINT languages_module_id_key UNIQUE (module_id);


--
-- Name: languages languages_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.languages
    ADD CONSTRAINT languages_pkey PRIMARY KEY (id);


--
-- Name: logins logins_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.logins
    ADD CONSTRAINT logins_pkey PRIMARY KEY (username);


--
-- Name: messages messages_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.messages
    ADD CONSTRAINT messages_pkey PRIMARY KEY (id);


--
-- Name: participants participants_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.participants
    ADD CONSTRAINT participants_pkey PRIMARY KEY (contest, team);


--
-- Name: print_jobs print_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.print_jobs
    ADD CONSTRAINT print_jobs_pkey PRIMARY KEY (id);


--
-- Name: problems problems_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.problems
    ADD CONSTRAINT problems_pkey PRIMARY KEY (contest_id, id);


--
-- Name: results results_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.results
    ADD CONSTRAINT results_pkey PRIMARY KEY (testing_id, test_id);


--
-- Name: schools schools_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.schools
    ADD CONSTRAINT schools_pkey PRIMARY KEY (id);


--
-- Name: submits submits_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.submits
    ADD CONSTRAINT submits_pkey PRIMARY KEY (id);


--
-- Name: teams teams_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.teams
    ADD CONSTRAINT teams_pkey PRIMARY KEY (id);


--
-- Name: testings testings_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.testings
    ADD CONSTRAINT testings_pkey PRIMARY KEY (id);


--
-- Name: waiter_task_record waiter_task_record_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.waiter_task_record
    ADD CONSTRAINT waiter_task_record_pkey PRIMARY KEY (id, room);


--
-- Name: waiter_tasks waiter_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.waiter_tasks
    ADD CONSTRAINT waiter_tasks_pkey PRIMARY KEY (id);


--
-- Name: fki_submits_teams_fkey; Type: INDEX; Schema: public; Owner: contest
--

CREATE INDEX fki_submits_teams_fkey ON public.submits USING btree (team_id);


--
-- Name: problems contest_id; Type: FK CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.problems
    ADD CONSTRAINT contest_id FOREIGN KEY (contest_id) REFERENCES public.contests(id) ON DELETE CASCADE;


--
-- Name: results results_testing_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.results
    ADD CONSTRAINT results_testing_id_fkey FOREIGN KEY (testing_id) REFERENCES public.testings(id) ON DELETE CASCADE;


--
-- Name: submits submits_contest_fkey; Type: FK CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.submits
    ADD CONSTRAINT submits_contest_fkey FOREIGN KEY (contest) REFERENCES public.contests(id);


--
-- Name: submits submits_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.submits
    ADD CONSTRAINT submits_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.teams(id) ON DELETE CASCADE;


--
-- Name: teams teams_school_fkey; Type: FK CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.teams
    ADD CONSTRAINT teams_school_fkey FOREIGN KEY (school) REFERENCES public.schools(id);


--
-- Name: testings testings_submit_fkey; Type: FK CONSTRAINT; Schema: public; Owner: contest
--

ALTER TABLE ONLY public.testings
    ADD CONSTRAINT testings_submit_fkey FOREIGN KEY (submit) REFERENCES public.submits(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

