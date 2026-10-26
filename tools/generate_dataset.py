#!/usr/bin/env python3
"""
Generates the learning dataset: DDL (a Flyway migration) and seed data.

    python3 tools/generate_dataset.py

Writes two files:

  backend/src/main/resources/db/migration/V2__learning_schema.sql
      Table definitions plus the grants that give the learner role SELECT and
      nothing else. Flyway checksums its migrations, so once this file has been
      applied anywhere it must be treated as immutable: change the dataset
      shape with a new V-numbered migration instead of editing this one.

  backend/src/main/resources/db/learning/seed.sql
      TRUNCATE followed by INSERTs. Re-runnable by design — "seed the database
      for the first time" and "reset the dataset" are the same operation, which
      is the only way to be sure the reset button really restores the state the
      lessons were written against.

Two schemas are produced from one table definition:

  learning      the dataset every lesson, example and problem is written
                against.
  learning_alt  the same tables with different rows, chosen to break naive
                answers: ties, NULLs, an empty table, duplicate keys, entities
                with no related rows. Answer evaluation runs the candidate's
                query against BOTH schemas, so a query that hardcodes a value
                it saw in `learning` fails on `learning_alt`.

Everything here is deterministic. There is no call to random() without a fixed
seed and no reference to the current date, so regenerating produces a
byte-identical file and the expected outputs recorded in the curriculum stay
valid.
"""
import pathlib
import random
from datetime import date, timedelta

ROOT = pathlib.Path(__file__).resolve().parents[1]
DDL_OUT = ROOT / "backend/src/main/resources/db/migration/V2__learning_schema.sql"
SEED_OUT = ROOT / "backend/src/main/resources/db/learning/seed.sql"

SCHEMAS = ["learning", "learning_alt"]

# --------------------------------------------------------------------------- #
#  Table definitions                                                          #
# --------------------------------------------------------------------------- #
#  (name, body, comment). %s is substituted with the schema name so the two
#  schemas cannot drift apart.

TABLES = [
    ("departments", """
    dept_id        integer       primary key,
    dept_name      text          not null unique,
    location       text,
    budget         numeric(12,2)
""", "Six departments. One of them deliberately has no employees, so a LEFT JOIN "
     "from departments is observably different from an INNER JOIN."),

    ("employees", """
    emp_id         integer       primary key,
    first_name     text          not null,
    last_name      text          not null,
    email          text          not null unique,
    dept_id        integer       references %s.departments(dept_id),
    manager_id     integer       references %s.employees(emp_id),
    job_title      text          not null,
    salary         numeric(10,2) not null check (salary > 0),
    hire_date      date          not null,
    is_active      boolean       not null default true
""", "A four-level reporting hierarchy with a NULL manager at the top, one employee "
     "with no department, deliberate salary ties, and employees who out-earn their "
     "manager."),

    ("projects", """
    project_id     integer       primary key,
    project_name   text          not null,
    dept_id        integer       references %s.departments(dept_id),
    start_date     date          not null,
    end_date       date,
    budget         numeric(12,2) not null
""", "A NULL end_date means the project is still running."),

    ("employee_projects", """
    emp_id         integer       not null references %s.employees(emp_id),
    project_id     integer       not null references %s.projects(project_id),
    role           text          not null,
    allocation_pct integer       not null check (allocation_pct between 1 and 100),
    primary key (emp_id, project_id)
""", "The junction table for the many-to-many between employees and projects."),

    ("categories", """
    category_id          integer primary key,
    category_name        text    not null,
    parent_category_id   integer references %s.categories(category_id)
""", "A self-referencing hierarchy, three levels deep, for recursive CTEs. One leaf "
     "category has no products."),

    ("products", """
    product_id     integer       primary key,
    product_name   text          not null,
    category_id    integer       references %s.categories(category_id),
    unit_price     numeric(10,2) not null check (unit_price >= 0),
    units_in_stock integer       not null default 0,
    discontinued   boolean       not null default false,
    attributes     jsonb
""", "attributes is JSONB so the JSON lessons have something real to query. Several "
     "products were never ordered."),

    ("customers", """
    customer_id    integer  primary key,
    company_name   text     not null,
    contact_name   text,
    country        text     not null,
    city           text,
    signup_date    date     not null,
    segment        text     not null
""", "Two customers have never placed an order, and one contact_name is NULL."),

    ("orders", """
    order_id         integer primary key,
    customer_id      integer not null references %s.customers(customer_id),
    employee_id      integer references %s.employees(emp_id),
    order_date       date    not null,
    ship_date        date,
    status           text    not null,
    shipping_country text    not null
""", "A NULL ship_date means the order has not shipped. Statuses include cancelled "
     "orders, which most naive revenue queries forget to exclude."),

    ("order_items", """
    order_id    integer       not null references %s.orders(order_id),
    product_id  integer       not null references %s.products(product_id),
    quantity    integer       not null check (quantity > 0),
    unit_price  numeric(10,2) not null,
    discount    numeric(4,3)  not null default 0 check (discount between 0 and 0.5),
    primary key (order_id, product_id)
""", "unit_price is stored per line rather than read from products, because the price "
     "at the time of the order is not the price today. Line revenue is "
     "quantity * unit_price * (1 - discount)."),

    ("payments", """
    payment_id integer       primary key,
    order_id   integer       not null references %s.orders(order_id),
    paid_at    timestamp     not null,
    amount     numeric(10,2) not null,
    method     text          not null
""", "An order can have several partial payments, or none at all."),

    ("user_activity", """
    activity_id   integer primary key,
    customer_id   integer not null references %s.customers(customer_id),
    activity_date date    not null,
    activity_type text    not null
""", "Deliberately gappy daily activity, for consecutive-date and gaps-and-islands "
     "questions."),

    ("account_status_history", """
    history_id     integer   primary key,
    customer_id    integer   not null references %s.customers(customer_id),
    status         text      not null,
    effective_from timestamp not null
""", "Append-only status changes. Finding the current status per customer is the "
     "latest-record-per-entity pattern."),

    ("daily_sales", """
    sale_date date          not null,
    region    text          not null,
    revenue   numeric(12,2) not null,
    primary key (sale_date, region)
""", "Ninety consecutive days for two regions, for running totals, moving averages "
     "and month-over-month growth."),

    ("contacts", """
    contact_id integer   primary key,
    email      text      not null,
    full_name  text      not null,
    created_at timestamp not null
""", "No unique constraint on email, on purpose: this is the table the "
     "find-and-remove-duplicates questions operate on."),

    ("exam_scores", """
    student_id integer not null,
    subject    text    not null,
    score      integer not null check (score between 0 and 100),
    primary key (student_id, subject)
""", "Contains ties and both an odd and an even number of rows per subject, so median "
     "and percentile answers have to handle both."),
]

ORDER = [t[0] for t in TABLES]


# --------------------------------------------------------------------------- #
#  SQL literal helpers                                                        #
# --------------------------------------------------------------------------- #
def lit(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, date):
        return f"'{v.isoformat()}'"
    return "'" + str(v).replace("'", "''") + "'"


def insert(schema, table, columns, rows):
    if not rows:
        return [f"-- {schema}.{table}: intentionally left empty\n"]
    out = [f"insert into {schema}.{table} ({', '.join(columns)}) values"]
    body = [f"  ({', '.join(lit(v) for v in r)})" for r in rows]
    out.append(",\n".join(body) + ";\n")
    return out


# --------------------------------------------------------------------------- #
#  The primary dataset: learning                                              #
# --------------------------------------------------------------------------- #
def primary_dataset():
    d = {}

    d["departments"] = (
        ["dept_id", "dept_name", "location", "budget"],
        [
            (10, "Engineering", "Bengaluru", 4200000.00),
            (20, "Sales", "Mumbai", 2600000.00),
            (30, "Marketing", "Mumbai", 1450000.00),
            (40, "Finance", "Pune", 1800000.00),
            (50, "Customer Support", "Hyderabad", 1200000.00),
            # No employee is assigned to department 60. Any query that joins
            # employees to departments with an INNER JOIN silently loses it.
            (60, "Research", "Bengaluru", 900000.00),
        ])

    # (emp_id, first, last, dept, manager, title, salary, hire_date, active)
    #
    # Shape of the data, stated so the lessons can rely on it:
    #   * emp 1 has no manager (the CEO) and no department.
    #   * emps 14 and 15 have identical salaries, as do 25 and 26 - ties.
    #   * emp 7 earns more than manager 3; emp 12 earns more than manager 4.
    #   * emp 40 has no department.
    #   * emps 33 and 38 are inactive.
    emp = [
        (1,  "Asha",    "Raman",     None, None, "Chief Executive Officer", 320000, date(2015, 3, 2),  True),
        (2,  "Vikram",  "Shetty",    10,   1,    "VP Engineering",          245000, date(2016, 1, 18), True),
        (3,  "Neha",    "Kulkarni",  20,   1,    "VP Sales",                238000, date(2016, 6, 1),  True),
        (4,  "Imran",   "Qureshi",   30,   1,    "VP Marketing",            205000, date(2017, 2, 13), True),
        (5,  "Sunita",  "Bose",      40,   1,    "VP Finance",              212000, date(2016, 9, 5),  True),
        (6,  "Rahul",   "Menon",     50,   1,    "VP Support",              178000, date(2018, 4, 23), True),

        (7,  "Daniel",  "Fernandes", 20,   3,    "Enterprise Account Lead", 251000, date(2018, 7, 9),  True),
        (8,  "Priya",   "Nair",      10,   2,    "Principal Engineer",      198000, date(2017, 11, 6), True),
        (9,  "Arjun",   "Deshpande", 10,   2,    "Engineering Manager",     186000, date(2018, 3, 19), True),
        (10, "Fatima",  "Sheikh",    10,   2,    "Engineering Manager",     184000, date(2019, 1, 7),  True),
        (11, "Kiran",   "Rao",       40,   5,    "Finance Manager",         152000, date(2019, 5, 20), True),
        (12, "Meera",   "Iyer",      30,   4,    "Brand Director",          211000, date(2018, 10, 1), True),
        (13, "Sanjay",  "Gupta",     50,   6,    "Support Manager",         131000, date(2019, 8, 12), True),

        (14, "Rohit",   "Varma",     10,   9,    "Senior Engineer",         148000, date(2020, 2, 3),  True),
        (15, "Ananya",  "Pillai",    10,   9,    "Senior Engineer",         148000, date(2020, 2, 3),  True),
        (16, "Tarun",   "Bhatt",     10,   9,    "Engineer",                121000, date(2021, 6, 14), True),
        (17, "Leela",   "Krishnan",  10,   10,   "Senior Engineer",         143000, date(2020, 9, 28), True),
        (18, "Zoya",    "Ahmed",     10,   10,   "Engineer",                118000, date(2022, 1, 10), True),
        (19, "Nikhil",  "Joshi",     10,   10,   "Engineer",                112000, date(2022, 7, 25), True),
        (20, "Harpreet", "Singh",    10,   9,    "Junior Engineer",          89000, date(2023, 3, 6),  True),

        (21, "Gaurav",  "Malhotra",  20,   7,    "Account Executive",       134000, date(2019, 11, 4), True),
        (22, "Shruti",  "Desai",     20,   7,    "Account Executive",       129000, date(2020, 5, 18), True),
        (23, "Arnav",   "Chatterjee", 20,  3,    "Sales Development Rep",    82000, date(2022, 2, 21), True),
        (24, "Pooja",   "Reddy",     20,   3,    "Sales Development Rep",    79000, date(2022, 9, 12), True),

        (25, "Farhan",  "Ali",       30,   12,   "Content Strategist",       97000, date(2021, 4, 5),  True),
        (26, "Divya",   "Menon",     30,   12,   "Content Strategist",       97000, date(2021, 4, 5),  True),
        (27, "Ishaan",  "Kapoor",    30,   4,    "Growth Analyst",          104000, date(2021, 10, 11), True),

        (28, "Ritu",    "Agarwal",   40,   11,   "Financial Analyst",        98000, date(2020, 8, 17), True),
        (29, "Vivek",   "Shah",      40,   11,   "Financial Analyst",        94000, date(2021, 1, 25), True),
        (30, "Manisha", "Dutta",     40,   5,    "Controller",              139000, date(2019, 2, 11), True),

        (31, "Amit",    "Trivedi",   50,   13,   "Support Engineer",         76000, date(2021, 7, 19), True),
        (32, "Sneha",   "Pawar",     50,   13,   "Support Engineer",         74000, date(2022, 3, 14), True),
        (33, "Kabir",   "Sen",       50,   13,   "Support Engineer",         71000, date(2022, 11, 7), False),
        (34, "Lakshmi", "Narayanan", 50,   6,    "Support Lead",            108000, date(2020, 6, 2),  True),

        (35, "Yusuf",   "Khan",      10,   8,    "Staff Engineer",          167000, date(2019, 9, 30), True),
        (36, "Ayesha",  "Patel",     10,   8,    "Staff Engineer",          162000, date(2020, 1, 13), True),
        (37, "Dev",     "Chauhan",   20,   7,    "Account Executive",       126000, date(2021, 2, 8),  True),
        (38, "Nandini", "Rawat",     30,   12,   "Designer",                 91000, date(2021, 8, 23), False),
        (39, "Suresh",  "Kamath",    40,   30,   "Accountant",               83000, date(2022, 5, 16), True),
        # No department: an employee the company has not slotted anywhere yet.
        (40, "Tanvi",   "Bakshi",    None, 1,    "Chief of Staff",          145000, date(2023, 1, 9),  True),
    ]
    d["employees"] = (
        ["emp_id", "first_name", "last_name", "email", "dept_id", "manager_id",
         "job_title", "salary", "hire_date", "is_active"],
        [(e[0], e[1], e[2], f"{e[1].lower()}.{e[2].lower().replace(' ', '')}@example.com",
          e[3], e[4], e[5], f"{e[6]:.2f}", e[7], e[8]) for e in emp])

    d["projects"] = (
        ["project_id", "project_name", "dept_id", "start_date", "end_date", "budget"],
        [
            (101, "Payments Rewrite",      10, date(2023, 1, 16), date(2023, 11, 30), 820000.00),
            (102, "Mobile App v3",         10, date(2023, 5, 2),  None,               640000.00),
            (103, "Data Platform",         10, date(2024, 1, 8),  None,               910000.00),
            (104, "Enterprise Expansion",  20, date(2023, 3, 1),  date(2024, 2, 29),  470000.00),
            (105, "Brand Refresh",         30, date(2023, 9, 11), date(2024, 4, 30),  315000.00),
            (106, "Cost Audit",            40, date(2024, 2, 5),  None,               180000.00),
            (107, "Support Automation",    50, date(2024, 3, 18), None,               240000.00),
        ])

    d["employee_projects"] = (
        ["emp_id", "project_id", "role", "allocation_pct"],
        [
            (8, 101, "Tech Lead", 60), (14, 101, "Engineer", 100), (15, 101, "Engineer", 80),
            (35, 101, "Architect", 40), (9, 101, "Manager", 25),
            (16, 102, "Engineer", 100), (17, 102, "Engineer", 100), (18, 102, "Engineer", 70),
            (10, 102, "Manager", 30), (36, 102, "Architect", 50),
            (8, 103, "Tech Lead", 40), (19, 103, "Engineer", 100), (20, 103, "Engineer", 100),
            (35, 103, "Architect", 60), (36, 103, "Architect", 50),
            (7, 104, "Lead", 50), (21, 104, "Account Executive", 100),
            (22, 104, "Account Executive", 100), (37, 104, "Account Executive", 60),
            (12, 105, "Director", 40), (25, 105, "Content", 100), (26, 105, "Content", 100),
            (27, 105, "Analyst", 50),
            (11, 106, "Lead", 50), (28, 106, "Analyst", 100), (29, 106, "Analyst", 100),
            (13, 107, "Lead", 60), (31, 107, "Engineer", 100), (32, 107, "Engineer", 100),
        ])

    d["categories"] = (
        ["category_id", "category_name", "parent_category_id"],
        [
            (1, "Electronics", None),
            (2, "Computers", 1),
            (3, "Laptops", 2),
            (4, "Accessories", 2),
            (5, "Audio", 1),
            (6, "Home", None),
            (7, "Kitchen", 6),
            (8, "Furniture", 6),
            # Nothing has ever been filed under Lighting.
            (9, "Lighting", 6),
        ])

    prod = [
        (201, "Meridian 14 Laptop",      3, 94999.00, 24,  False, '{"cpu":"i7","ram_gb":16,"warranty_months":24,"colours":["silver","graphite"]}'),
        (202, "Meridian 16 Laptop",      3, 134999.00, 11, False, '{"cpu":"i9","ram_gb":32,"warranty_months":24,"colours":["graphite"]}'),
        (203, "Orbit Ultrabook",         3, 78999.00, 37,  False, '{"cpu":"i5","ram_gb":16,"warranty_months":12,"colours":["silver"]}'),
        (204, "Vertex Gaming Laptop",    3, 169999.00, 6,  False, '{"cpu":"i9","ram_gb":32,"gpu":"RTX","warranty_months":24}'),
        (205, "Quill Wireless Mouse",    4, 2499.00, 310, False, '{"connectivity":"bluetooth","warranty_months":12}'),
        (206, "Quill Mechanical Keyboard", 4, 7999.00, 142, False, '{"switch":"brown","backlit":true,"warranty_months":24}'),
        (207, "Anchor USB-C Hub",        4, 4299.00, 88,  False, '{"ports":7,"warranty_months":12}'),
        (208, "Anchor Laptop Stand",     4, 3199.00, 65,  False, '{"material":"aluminium"}'),
        (209, "Beacon 27 Monitor",       2, 32999.00, 19, False, '{"panel":"IPS","resolution":"2560x1440","warranty_months":36}'),
        (210, "Beacon 32 Monitor",       2, 51999.00, 8,  False, '{"panel":"IPS","resolution":"3840x2160","warranty_months":36}'),
        (211, "Cadence Headphones",      5, 18999.00, 54, False, '{"noise_cancelling":true,"warranty_months":12,"colours":["black","sand"]}'),
        (212, "Cadence Earbuds",         5, 8999.00, 203, False, '{"noise_cancelling":true,"warranty_months":12}'),
        (213, "Resonance Soundbar",      5, 24999.00, 17, False, '{"channels":"3.1","warranty_months":24}'),
        (214, "Resonance Bookshelf Speakers", 5, 36999.00, 9, False, '{"channels":"2.0","warranty_months":24}'),
        (215, "Kettle Pro 1.7L",         7, 3499.00, 120, False, '{"capacity_l":1.7,"warranty_months":24}'),
        (216, "Press Coffee Maker",      7, 5999.00, 74, False, '{"capacity_cups":8,"warranty_months":12}'),
        (217, "Blade Food Processor",    7, 11999.00, 31, False, '{"power_w":800,"warranty_months":24}'),
        (218, "Arbor Desk",              8, 28999.00, 14, False, '{"material":"oak","adjustable":true}'),
        (219, "Arbor Office Chair",      8, 21999.00, 22, False, '{"material":"mesh","warranty_months":60}'),
        (220, "Arbor Bookshelf",         8, 15999.00, 18, False, '{"material":"oak","shelves":5}'),
        # Discontinued, and still has historic orders.
        (221, "Meridian 13 Laptop (2022)", 3, 71999.00, 0, True, '{"cpu":"i5","ram_gb":8,"warranty_months":12}'),
        # The next four have never been ordered. "Products never sold" is a
        # standard interview question and needs real rows to find.
        (222, "Quill Trackpad",          4, 6499.00, 40, False, '{"connectivity":"bluetooth"}'),
        (223, "Cadence Studio Monitor",  5, 44999.00, 3, False, '{"channels":"2.0"}'),
        (224, "Press Milk Frother",      7, 2799.00, 56, False, '{"warranty_months":12}'),
        (225, "Arbor Side Table",        8, 8999.00, 27, False, None),
    ]
    d["products"] = (
        ["product_id", "product_name", "category_id", "unit_price",
         "units_in_stock", "discontinued", "attributes"],
        [(p[0], p[1], p[2], f"{p[3]:.2f}", p[4], p[5], p[6]) for p in prod])

    cust = [
        (301, "Northwind Traders",   "Ellen Marsh",    "United Kingdom", "Leeds",     date(2022, 4, 11), "Enterprise"),
        (302, "Blue Harbour Retail", "Tomas Lindqvist", "Sweden",        "Malmo",     date(2022, 6, 3),  "Enterprise"),
        (303, "Kestrel Supplies",    "Anita Rao",      "India",          "Bengaluru", date(2022, 7, 29), "Mid-Market"),
        (304, "Stonebridge Ltd",     "Gordon Pike",    "United Kingdom", "Bristol",   date(2022, 9, 14), "Mid-Market"),
        (305, "Alpine Systems",      "Marta Keller",   "Germany",        "Munich",    date(2022, 11, 2), "Enterprise"),
        (306, "Coral Bay Imports",   None,             "Australia",      "Cairns",    date(2023, 1, 20), "Small Business"),
        (307, "Vermillion Design",   "Paulo Ribeiro",  "Portugal",       "Porto",     date(2023, 2, 8),  "Small Business"),
        (308, "Granite Peak Co",     "Hannah Vogel",   "Germany",        "Dresden",   date(2023, 3, 17), "Mid-Market"),
        (309, "Saffron Kitchens",    "Ravi Menon",     "India",          "Chennai",   date(2023, 4, 25), "Small Business"),
        (310, "Meridian Logistics",  "Clara Dupont",   "France",         "Lyon",      date(2023, 5, 30), "Enterprise"),
        (311, "Thornfield Group",    "Owen Carter",    "United Kingdom", "Manchester", date(2023, 7, 12), "Mid-Market"),
        (312, "Pinewood Studios Ltd", "Sarah Doyle",   "Ireland",        "Galway",    date(2023, 8, 21), "Small Business"),
        (313, "Harbourline AB",      "Erik Nyberg",    "Sweden",         "Gothenburg", date(2023, 9, 6), "Mid-Market"),
        (314, "Cobalt Analytics",    "Ivan Petrov",    "Netherlands",    "Utrecht",   date(2023, 10, 19), "Enterprise"),
        (315, "Juniper Foods",       "Maya Sharma",    "India",          "Pune",      date(2023, 11, 27), "Small Business"),
        (316, "Westgate Electric",   "Liam O'Connor",  "Ireland",        "Cork",      date(2024, 1, 15), "Mid-Market"),
        (317, "Silverline Retail",   "Nora Haugen",    "Norway",         "Bergen",    date(2024, 2, 23), "Mid-Market"),
        (318, "Draycott & Sons",     "Peter Draycott", "United Kingdom", "York",      date(2024, 3, 11), "Small Business"),
        (319, "Lumen Workspaces",    "Agnes Bakker",   "Netherlands",    "Rotterdam", date(2024, 4, 2),  "Enterprise"),
        (320, "Terra Verde SL",      "Lucia Moreno",   "Spain",          "Valencia",  date(2024, 5, 20), "Small Business"),
        # 321 and 322 have never placed an order.
        (321, "Hollowbrook Trading", "Martin Teague",  "United Kingdom", "Norwich",   date(2024, 6, 14), "Small Business"),
        (322, "Nimbus Cloud Ltd",    "Grace Ofori",    "Ireland",        "Dublin",    date(2024, 7, 1),  "Mid-Market"),
    ]
    d["customers"] = (
        ["customer_id", "company_name", "contact_name", "country", "city",
         "signup_date", "segment"], cust)

    # ---- orders and order_items -------------------------------------------
    rng = random.Random(20240719)
    sales_reps = [7, 21, 22, 23, 24, 37]
    buyers = [c[0] for c in cust if c[0] not in (321, 322)]
    sellable = [p[0] for p in prod if p[0] not in (222, 223, 224, 225)]
    statuses = (["completed"] * 14) + ["shipped", "shipped", "pending", "cancelled"]

    orders, items = [], []
    order_id, start = 1001, date(2023, 1, 9)
    for day_offset in range(0, 700, 5):
        od = start + timedelta(days=day_offset)
        if od > date(2024, 12, 20):
            break
        for _ in range(rng.randint(0, 2)):
            cust_id = rng.choice(buyers)
            status = rng.choice(statuses)
            ship = None if status in ("pending", "cancelled") else od + timedelta(days=rng.randint(1, 9))
            country = next(c[3] for c in cust if c[0] == cust_id)
            orders.append((order_id, cust_id, rng.choice(sales_reps), od, ship, status, country))
            for pid in rng.sample(sellable, rng.randint(1, 4)):
                base = next(p[3] for p in prod if p[0] == pid)
                # The price charged drifts from the catalogue price, which is why
                # order_items carries its own unit_price.
                charged = round(base * rng.choice([0.95, 1.00, 1.00, 1.00, 1.05]), 2)
                items.append((order_id, pid, rng.randint(1, 6), f"{charged:.2f}",
                              rng.choice([0, 0, 0, 0.05, 0.10, 0.15])))
            order_id += 1

    d["orders"] = (["order_id", "customer_id", "employee_id", "order_date",
                    "ship_date", "status", "shipping_country"], orders)
    d["order_items"] = (["order_id", "product_id", "quantity", "unit_price",
                         "discount"], items)

    # ---- payments ----------------------------------------------------------
    pay, pid_seq = [], 5001
    methods = ["card", "bank_transfer", "card", "card", "direct_debit"]
    for o in orders:
        if o[5] == "cancelled":
            continue
        total = sum(i[2] * float(i[3]) * (1 - float(i[4])) for i in items if i[0] == o[0])
        if o[5] == "pending" and rng.random() < 0.6:
            continue
        if rng.random() < 0.18:  # split into two instalments
            first = round(total * 0.4, 2)
            pay.append((pid_seq, o[0], f"{o[3].isoformat()} 10:15:00", f"{first:.2f}", rng.choice(methods)))
            pid_seq += 1
            pay.append((pid_seq, o[0], f"{(o[3] + timedelta(days=21)).isoformat()} 10:15:00",
                        f"{round(total - first, 2):.2f}", rng.choice(methods)))
        else:
            pay.append((pid_seq, o[0], f"{(o[3] + timedelta(days=2)).isoformat()} 14:40:00",
                        f"{round(total, 2):.2f}", rng.choice(methods)))
        pid_seq += 1
    d["payments"] = (["payment_id", "order_id", "paid_at", "amount", "method"], pay)

    # ---- user_activity -----------------------------------------------------
    # Deliberately gappy: customer 301 has one long unbroken run, 303 has two
    # separate runs, and most others are sparse. Gaps-and-islands questions are
    # only interesting when the islands have different lengths.
    act, aid = [], 7001
    kinds = ["login", "login", "login", "search", "purchase", "support_ticket"]
    base = date(2024, 5, 1)
    for off in range(0, 12):                      # 301: 12 consecutive days
        act.append((aid, 301, base + timedelta(days=off), rng.choice(kinds))); aid += 1
    for off in [0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 18, 19]:   # 303: three islands
        act.append((aid, 303, base + timedelta(days=off), rng.choice(kinds))); aid += 1
    for off in [2, 3, 4, 5, 6]:                   # 305: one run of five
        act.append((aid, 305, base + timedelta(days=off), rng.choice(kinds))); aid += 1
    for cid in [302, 304, 306, 308, 310, 311, 314, 316, 319]:
        for off in sorted(rng.sample(range(0, 30), rng.randint(2, 6))):
            act.append((aid, cid, base + timedelta(days=off), rng.choice(kinds))); aid += 1
    act.sort(key=lambda r: (r[1], r[2]))
    act = [(i + 7001, r[1], r[2], r[3]) for i, r in enumerate(act)]
    d["user_activity"] = (["activity_id", "customer_id", "activity_date", "activity_type"], act)

    # ---- account_status_history -------------------------------------------
    hist, hid = [], 8001
    plan = {
        301: [("trial", "2022-04-11 09:00:00"), ("active", "2022-05-11 09:00:00"),
              ("active", "2023-04-11 09:00:00")],
        302: [("trial", "2022-06-03 09:00:00"), ("active", "2022-07-03 09:00:00"),
              ("suspended", "2024-02-14 16:30:00"), ("active", "2024-03-01 11:00:00")],
        303: [("trial", "2022-07-29 09:00:00"), ("active", "2022-08-29 09:00:00")],
        305: [("active", "2022-11-02 09:00:00"), ("churned", "2024-08-15 12:00:00")],
        307: [("trial", "2023-02-08 09:00:00"), ("churned", "2023-05-08 09:00:00")],
        310: [("trial", "2023-05-30 09:00:00"), ("active", "2023-06-29 09:00:00"),
              ("suspended", "2024-09-02 08:45:00")],
        314: [("active", "2023-10-19 09:00:00")],
        319: [("trial", "2024-04-02 09:00:00"), ("active", "2024-05-02 09:00:00")],
        # Two rows at the identical timestamp: "latest row per customer" has to
        # decide what to do about a tie, and ROW_NUMBER will pick arbitrarily.
        321: [("trial", "2024-06-14 09:00:00"), ("active", "2024-06-14 09:00:00")],
    }
    for cid, rows in plan.items():
        for status, ts in rows:
            hist.append((hid, cid, status, ts)); hid += 1
    d["account_status_history"] = (
        ["history_id", "customer_id", "status", "effective_from"], hist)

    # ---- daily_sales -------------------------------------------------------
    # Ninety consecutive days with a weekly shape and a visible step change at
    # the start of March, so month-over-month growth is not just noise.
    sales = []
    for off in range(90):
        sd = date(2024, 1, 1) + timedelta(days=off)
        weekday_factor = 0.55 if sd.weekday() >= 5 else 1.0
        step = 1.28 if sd.month == 3 else (1.09 if sd.month == 2 else 1.0)
        for region, scale in (("North", 1.0), ("South", 0.72)):
            revenue = 42000 * weekday_factor * step * scale * (1 + (rng.random() - 0.5) * 0.18)
            sales.append((sd, region, f"{round(revenue, 2):.2f}"))
    d["daily_sales"] = (["sale_date", "region", "revenue"], sales)

    # ---- contacts ----------------------------------------------------------
    # Exact-duplicate emails with different ids and different created_at, which
    # is what lets "keep the earliest, delete the rest" be a real question.
    d["contacts"] = (
        ["contact_id", "email", "full_name", "created_at"],
        [
            (9001, "ellen.marsh@northwind.example",  "Ellen Marsh",   "2023-01-04 08:12:00"),
            (9002, "tomas.l@blueharbour.example",    "Tomas Lindqvist", "2023-01-05 09:40:00"),
            (9003, "ellen.marsh@northwind.example",  "E. Marsh",      "2023-03-19 14:05:00"),
            (9004, "anita.rao@kestrel.example",      "Anita Rao",     "2023-02-11 11:22:00"),
            (9005, "gordon.pike@stonebridge.example", "Gordon Pike",  "2023-02-28 16:48:00"),
            (9006, "ellen.marsh@northwind.example",  "Ellen M.",      "2023-07-02 10:31:00"),
            (9007, "anita.rao@kestrel.example",      "Anita R.",      "2023-09-14 12:09:00"),
            (9008, "marta.keller@alpine.example",    "Marta Keller",  "2023-04-06 07:55:00"),
            (9009, "tomas.l@blueharbour.example",    "Tomas L.",      "2024-01-23 13:17:00"),
            (9010, "paulo.r@vermillion.example",     "Paulo Ribeiro", "2023-06-30 15:02:00"),
            (9011, "hannah.vogel@granitepeak.example", "Hannah Vogel", "2023-08-08 09:26:00"),
            (9012, "ravi.menon@saffron.example",     "Ravi Menon",    "2023-10-17 18:44:00"),
        ])

    # ---- exam_scores -------------------------------------------------------
    # Mathematics has 9 rows (odd), Physics 10 (even) and Chemistry 8 with a
    # three-way tie. A median answer that only handles one parity is wrong.
    scores = []
    maths = [71, 88, 64, 95, 52, 88, 79, 61, 83]
    physics = [66, 74, 91, 58, 74, 82, 69, 90, 77, 63]
    chemistry = [80, 80, 80, 67, 92, 55, 73, 86]
    for i, s in enumerate(maths):
        scores.append((401 + i, "Mathematics", s))
    for i, s in enumerate(physics):
        scores.append((401 + i, "Physics", s))
    for i, s in enumerate(chemistry):
        scores.append((401 + i, "Chemistry", s))
    d["exam_scores"] = (["student_id", "subject", "score"], scores)

    return d


# --------------------------------------------------------------------------- #
#  The evaluation dataset: learning_alt                                       #
# --------------------------------------------------------------------------- #
def alt_dataset():
    """
    Same tables, different rows, chosen so that a query which happens to be
    right on `learning` by luck is wrong here.

    Every choice below has a purpose:
      * Engineering has a single employee, so "top 3 per department" must cope
        with a group that has fewer than three members.
      * Two employees share the maximum salary, so "second highest" must decide
        between DENSE_RANK (one answer) and OFFSET (another).
      * products is populated but order_items is EMPTY, so every aggregate over
        revenue has to survive an empty input.
      * One department has no employees and one employee has no department.
    """
    d = {}

    d["departments"] = (
        ["dept_id", "dept_name", "location", "budget"],
        [(10, "Engineering", "Remote", 500000.00),
         (20, "Sales", "Remote", 300000.00),
         (30, "Operations", None, None)])

    emp = [
        (1, "Ada",  "Lovelace", 10, None, "Director",       180000, date(2019, 1, 1), True),
        (2, "Alan", "Turing",   10, 1,    "Engineer",       180000, date(2020, 1, 1), True),
        (3, "Grace", "Hopper",  20, 1,    "Sales Lead",     150000, date(2020, 6, 1), True),
        (4, "Edsger", "Dijkstra", 20, 3,  "Account Exec",   150000, date(2021, 3, 1), True),
        (5, "Barbara", "Liskov", 20, 3,   "Account Exec",    90000, date(2022, 4, 1), False),
        (6, "Tony",  "Hoare",    None, 1, "Advisor",        120000, date(2023, 5, 1), True),
    ]
    d["employees"] = (
        ["emp_id", "first_name", "last_name", "email", "dept_id", "manager_id",
         "job_title", "salary", "hire_date", "is_active"],
        [(e[0], e[1], e[2], f"{e[1].lower()}.{e[2].lower()}@example.org",
          e[3], e[4], e[5], f"{e[6]:.2f}", e[7], e[8]) for e in emp])

    d["projects"] = (
        ["project_id", "project_name", "dept_id", "start_date", "end_date", "budget"],
        [(101, "Migration", 10, date(2024, 1, 1), None, 100000.00)])

    d["employee_projects"] = (
        ["emp_id", "project_id", "role", "allocation_pct"],
        [(1, 101, "Lead", 50), (2, 101, "Engineer", 100)])

    d["categories"] = (
        ["category_id", "category_name", "parent_category_id"],
        [(1, "Everything", None), (2, "Gadgets", 1)])

    d["products"] = (
        ["product_id", "product_name", "category_id", "unit_price",
         "units_in_stock", "discontinued", "attributes"],
        [(201, "Widget", 2, "100.00", 5, False, '{"warranty_months":6}'),
         (202, "Gizmo", 2, "250.00", 0, True, None),
         (203, "Doohickey", 1, "75.00", 12, False, '{"colours":["red"]}')])

    d["customers"] = (
        ["customer_id", "company_name", "contact_name", "country", "city",
         "signup_date", "segment"],
        [(301, "Solo Ltd", "Pat Reed", "Ireland", "Sligo", date(2024, 1, 1), "Small Business"),
         (302, "Duet GmbH", None, "Germany", "Kiel", date(2024, 2, 1), "Mid-Market"),
         (303, "Silent Co", "Kim Park", "Japan", "Kobe", date(2024, 3, 1), "Enterprise")])

    d["orders"] = (
        ["order_id", "customer_id", "employee_id", "order_date", "ship_date",
         "status", "shipping_country"],
        [(1001, 301, 3, date(2024, 4, 1), date(2024, 4, 3), "completed", "Ireland"),
         (1002, 301, 4, date(2024, 4, 1), None, "cancelled", "Ireland"),
         (1003, 302, None, date(2024, 5, 2), date(2024, 5, 9), "completed", "Germany")])

    # Intentionally empty. Any revenue query must return a row with NULL or
    # zero, or no rows at all - and the lesson text has to say which.
    d["order_items"] = (["order_id", "product_id", "quantity", "unit_price", "discount"], [])

    d["payments"] = (["payment_id", "order_id", "paid_at", "amount", "method"],
                     [(5001, 1001, "2024-04-03 10:00:00", "0.00", "card")])

    d["user_activity"] = (
        ["activity_id", "customer_id", "activity_date", "activity_type"],
        [(7001, 301, date(2024, 6, 1), "login"),
         (7002, 301, date(2024, 6, 2), "login"),
         (7003, 301, date(2024, 6, 2), "search"),
         (7004, 302, date(2024, 6, 5), "login")])

    d["account_status_history"] = (
        ["history_id", "customer_id", "status", "effective_from"],
        [(8001, 301, "trial", "2024-01-01 09:00:00"),
         (8002, 301, "active", "2024-02-01 09:00:00"),
         (8003, 302, "active", "2024-02-01 09:00:00")])

    d["daily_sales"] = (
        ["sale_date", "region", "revenue"],
        [(date(2024, 1, 1), "North", "1000.00"),
         (date(2024, 1, 2), "North", "1000.00"),
         (date(2024, 2, 1), "North", "2000.00"),
         (date(2024, 1, 1), "South", "500.00")])

    d["contacts"] = (
        ["contact_id", "email", "full_name", "created_at"],
        [(9001, "a@example.org", "A One", "2024-01-01 00:00:00"),
         (9002, "a@example.org", "A Two", "2024-01-02 00:00:00"),
         (9003, "b@example.org", "B One", "2024-01-03 00:00:00")])

    d["exam_scores"] = (
        ["student_id", "subject", "score"],
        [(401, "Mathematics", 50), (402, "Mathematics", 50),
         (403, "Physics", 90), (404, "Physics", 70), (405, "Physics", 80)])

    return d


# --------------------------------------------------------------------------- #
def write_ddl():
    L = [
        "-- Learning dataset schema.",
        "--",
        "-- GENERATED by tools/generate_dataset.py. Flyway checksums this file, so",
        "-- once it has been applied anywhere it is immutable: change the dataset",
        "-- shape in a new V-numbered migration rather than editing this one.",
        "--",
        "-- Two schemas with identical definitions:",
        "--   learning      every lesson, example and interview problem runs here.",
        "--   learning_alt  the same tables with rows chosen to break naive answers.",
        "--                 Answer evaluation runs a submission against both, so a",
        "--                 query that hardcodes a value from `learning` is caught.",
        "--",
        "-- ${learnerRole} is a Flyway placeholder resolved from configuration. The",
        "-- grants below are the entire set of privileges the playground role ever",
        "-- holds: USAGE on these two schemas and SELECT on these tables. It has no",
        "-- rights at all on the app schema, so a learner cannot read the stored",
        "-- solutions by querying for them.",
        "",
    ]
    for schema in SCHEMAS:
        L.append(f"create schema if not exists {schema};")
    L.append("")

    for schema in SCHEMAS:
        L += [f"-- {'=' * 73}", f"--  {schema}", f"-- {'=' * 73}", ""]
        for name, body, comment in TABLES:
            wrapped = []
            words, line = comment.split(), "--  "
            for w in words:
                if len(line) + len(w) + 1 > 78:
                    wrapped.append(line)
                    line = "--  " + w
                else:
                    line += ("" if line.endswith("  ") else " ") + w
            wrapped.append(line)
            L += wrapped
            L.append(f"create table {schema}.{name} ({(body % ((schema,) * body.count('%s'))).rstrip()}\n);")
            L.append("")

    L += ["-- " + "=" * 73, "--  Indexes", "-- " + "=" * 73,
          "--  Only on the primary schema. The lessons on EXPLAIN need a table that",
          "--  has an index and a table that does not, so the difference between a",
          "--  sequential scan and an index scan is observable rather than asserted.",
          ""]
    L += [
        "create index idx_employees_dept on learning.employees (dept_id);",
        "create index idx_employees_manager on learning.employees (manager_id);",
        "create index idx_orders_customer on learning.orders (customer_id);",
        "create index idx_orders_order_date on learning.orders (order_date);",
        "create index idx_order_items_product on learning.order_items (product_id);",
        "create index idx_products_category on learning.products (category_id);",
        "create index idx_user_activity_cust_date on learning.user_activity (customer_id, activity_date);",
        "-- contacts is deliberately left unindexed: the duplicate-detection lessons",
        "-- use it to show a sequential scan and a hash aggregate in EXPLAIN output.",
        "",
    ]

    L += ["-- " + "=" * 73, "--  Grants", "-- " + "=" * 73, ""]
    for schema in SCHEMAS:
        L += [
            f"grant usage on schema {schema} to \"${{learnerRole}}\";",
            f"grant select on all tables in schema {schema} to \"${{learnerRole}}\";",
            "-- Default privileges so a table added by a later migration is readable",
            "-- without anyone having to remember to grant it.",
            f"alter default privileges in schema {schema} "
            f"grant select on tables to \"${{learnerRole}}\";",
            "",
        ]
    L += [
        "-- Stated explicitly rather than relied upon: the learner role must never",
        "-- be able to write, and must never see the app schema.",
        "revoke create on schema learning, learning_alt from \"${learnerRole}\";",
        "",
    ]
    DDL_OUT.parent.mkdir(parents=True, exist_ok=True)
    DDL_OUT.write_text("\n".join(L))
    return sum(1 for _ in TABLES) * len(SCHEMAS)


def write_seed():
    datasets = {"learning": primary_dataset(), "learning_alt": alt_dataset()}
    L = [
        "-- Learning dataset seed data.",
        "--",
        "-- GENERATED by tools/generate_dataset.py. Deterministic: regenerating",
        "-- produces a byte-identical file, which is what lets the expected outputs",
        "-- recorded against every lesson example stay valid.",
        "--",
        "-- This is NOT a Flyway migration. It is executed by DatasetResetService,",
        "-- both to populate an empty database at startup and to serve the Reset",
        "-- Dataset button, so the two paths cannot diverge.",
        "--",
        "-- TRUNCATE ... RESTART IDENTITY CASCADE first, so the file is re-runnable",
        "-- and leaves no trace of whatever the previous state was.",
        "",
    ]
    total = 0
    for schema in SCHEMAS:
        qualified = ", ".join(f"{schema}.{t}" for t in reversed(ORDER))
        L.append(f"truncate table {qualified} restart identity cascade;")
    L.append("")

    for schema in SCHEMAS:
        L += [f"-- {'=' * 73}", f"--  {schema}", f"-- {'=' * 73}", ""]
        data = datasets[schema]
        for name in ORDER:
            cols, rows = data[name]
            total += len(rows)
            L += insert(schema, name, cols, rows)

    SEED_OUT.parent.mkdir(parents=True, exist_ok=True)
    SEED_OUT.write_text("\n".join(L))
    return total, datasets


if __name__ == "__main__":
    tables = write_ddl()
    rows, data = write_seed()
    print(f"  {DDL_OUT.relative_to(ROOT)}")
    print(f"      {tables} table definitions across {len(SCHEMAS)} schemas")
    print(f"  {SEED_OUT.relative_to(ROOT)}")
    print(f"      {rows} rows total")
    for schema in SCHEMAS:
        counts = ", ".join(f"{t}={len(data[schema][t][1])}" for t in ORDER)
        print(f"      {schema}: {counts}")
