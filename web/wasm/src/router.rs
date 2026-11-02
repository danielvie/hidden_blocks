//! Adapted from gpui-blocks/rust_gpui_blocks_simple/src/diagram_router.rs.
//! Grid A* retains its bend/congestion costs and reports failures, never a fallback through blocks.
use serde::{Deserialize, Serialize};
use std::{cmp::Reverse, collections::BinaryHeap};

const GRID: f64 = 12.;
const MARGIN: f64 = 96.;

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Serialize)]
struct Point {
    x: f64,
    y: f64,
}

#[derive(Deserialize)]
struct Block {
    id: String,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

impl Block {
    fn bounds(&self, clearance: f64) -> Rect {
        Rect {
            min: Point {
                x: self.x - clearance,
                y: self.y - clearance,
            },
            max: Point {
                x: self.x + self.width + clearance,
                y: self.y + self.height + clearance,
            },
        }
    }
}

#[derive(Deserialize)]
struct Endpoint {
    owner: String,
    #[serde(flatten)]
    point: Point,
}

#[derive(Deserialize)]
struct Wire {
    id: String,
    source: Endpoint,
    target: Endpoint,
}

#[derive(Deserialize)]
struct Request {
    blocks: Vec<Block>,
    wires: Vec<Wire>,
}

#[derive(Serialize)]
struct Route {
    id: String,
    points: Vec<Point>,
}

#[derive(Serialize)]
struct Response {
    routes: Vec<Route>,
}

struct Rect {
    min: Point,
    max: Point,
}

impl Rect {
    // Inclusive inflated bounds. Test full segments, not just their grid endpoints.
    fn hits(&self, a: Point, b: Point) -> bool {
        a.x.min(b.x) <= self.max.x
            && a.x.max(b.x) >= self.min.x
            && a.y.min(b.y) <= self.max.y
            && a.y.max(b.y) >= self.min.y
    }

    // Independently measured owner edges and terminals can differ after inverse zoom.
    // Tolerate only subpixel boundary noise; inflated obstacle checks remain exact.
    fn crosses_interior(&self, a: Point, b: Point) -> bool {
        const TOLERANCE: f64 = 0.001;
        a.x.min(b.x) < self.max.x - TOLERANCE
            && a.x.max(b.x) > self.min.x + TOLERANCE
            && a.y.min(b.y) < self.max.y - TOLERANCE
            && a.y.max(b.y) > self.min.y + TOLERANCE
    }
}

struct Grid {
    origin: Point,
    cols: usize,
    rows: usize,
    obstacles: Vec<Rect>,
    blocked: Vec<bool>,
}

impl Grid {
    fn new(request: &Request) -> Self {
        let obstacles: Vec<_> = request.blocks.iter().map(|b| b.bounds(GRID)).collect();
        let mut min = Point {
            x: f64::INFINITY,
            y: f64::INFINITY,
        };
        let mut max = Point {
            x: f64::NEG_INFINITY,
            y: f64::NEG_INFINITY,
        };
        for p in obstacles.iter().flat_map(|r| [r.min, r.max]).chain(
            request
                .wires
                .iter()
                .flat_map(|w| [w.source.point, w.target.point]),
        ) {
            min.x = min.x.min(p.x);
            min.y = min.y.min(p.y);
            max.x = max.x.max(p.x);
            max.y = max.y.max(p.y);
        }
        let origin = Point {
            x: ((min.x - MARGIN) / GRID).floor() * GRID,
            y: ((min.y - MARGIN) / GRID).floor() * GRID,
        };
        let cols = ((max.x + MARGIN - origin.x) / GRID).ceil() as usize + 1;
        let rows = ((max.y + MARGIN - origin.y) / GRID).ceil() as usize + 1;
        let mut grid = Self {
            origin,
            cols,
            rows,
            obstacles,
            blocked: vec![false; cols * rows],
        };
        for i in 0..grid.blocked.len() {
            let p = grid.point(i);
            grid.blocked[i] = grid.obstacles.iter().any(|r| r.hits(p, p));
        }
        grid
    }

    fn point(&self, i: usize) -> Point {
        Point {
            x: self.origin.x + (i % self.cols) as f64 * GRID,
            y: self.origin.y + (i / self.cols) as f64 * GRID,
        }
    }

    fn cell(&self, p: Point) -> usize {
        ((p.y - self.origin.y) / GRID).round() as usize * self.cols
            + ((p.x - self.origin.x) / GRID).round() as usize
    }
}

fn stub_clear(points: &[Point], owner: &str, blocks: &[Block]) -> bool {
    blocks.iter().any(|b| b.id == owner)
        && points.windows(2).all(|pair| {
            blocks.iter().all(|b| {
                if b.id == owner {
                    !b.bounds(0.).crosses_interior(pair[0], pair[1])
                } else {
                    !b.bounds(GRID).hits(pair[0], pair[1])
                }
            })
        })
}

pub(crate) fn route_impl(input: &str) -> Result<String, String> {
    let request: Request = serde_json::from_str(input).map_err(|e| e.to_string())?;
    if request.blocks.iter().any(|b| b.width < 0. || b.height < 0.) {
        return Err("Block dimensions must not be negative".into());
    }
    if request.wires.is_empty() {
        return serde_json::to_string(&Response { routes: vec![] }).map_err(|e| e.to_string());
    }
    let grid = Grid::new(&request);
    let mut traffic = vec![[0u32; 2]; grid.cols * grid.rows];
    let routes = request
        .wires
        .iter()
        .map(|wire| {
            let a = wire.source.point;
            let b = wire.target.point;
            // Keep measured endpoints exact. Move horizontally beyond owner clearance
            // before bridging vertically to the globally aligned routing grid.
            let start = Point {
                x: ((a.x + GRID * 2.) / GRID).ceil() * GRID,
                y: (a.y / GRID).round() * GRID,
            };
            let end = Point {
                x: ((b.x - GRID * 2.) / GRID).floor() * GRID,
                y: (b.y / GRID).round() * GRID,
            };
            let source_stub = [a, Point { x: start.x, y: a.y }, start];
            let target_stub = [end, Point { x: end.x, y: b.y }, b];
            let mut points = Vec::new();
            if stub_clear(&source_stub, &wire.source.owner, &request.blocks)
                && stub_clear(&target_stub, &wire.target.owner, &request.blocks)
            {
                if let Some(cells) = search(grid.cell(start), grid.cell(end), &grid, &traffic) {
                    points.extend(source_stub);
                    points.extend(cells.iter().map(|&i| grid.point(i)));
                    points.extend(target_stub);
                    for pair in cells.windows(2) {
                        let dir = usize::from(pair[0] / grid.cols != pair[1] / grid.cols);
                        traffic[pair[0]][dir] += 1;
                        traffic[pair[1]][dir] += 1;
                    }
                    points = simplify(points);
                }
            }
            Route {
                id: wire.id.clone(),
                points,
            }
        })
        .collect();
    serde_json::to_string(&Response { routes }).map_err(|e| e.to_string())
}

fn search(source: usize, target: usize, grid: &Grid, traffic: &[[u32; 2]]) -> Option<Vec<usize>> {
    if grid.blocked[source] || grid.blocked[target] {
        return None;
    }
    let cols = grid.cols;
    // State includes arrival axis so bends can be penalized correctly.
    let mut cost = vec![u32::MAX; cols * grid.rows * 2];
    let mut parent = vec![usize::MAX; cost.len()];
    let heuristic = |i: usize| {
        ((i % cols).abs_diff(target % cols) + (i / cols).abs_diff(target / cols)) as u32 * 10
    };
    let mut heap = BinaryHeap::new();
    cost[source * 2] = 0;
    heap.push(Reverse((heuristic(source), 0u32, source * 2)));
    while let Some(Reverse((_, g, state))) = heap.pop() {
        if g != cost[state] {
            continue;
        }
        let i = state / 2;
        let dir = state % 2;
        if i == target {
            let mut path = vec![i];
            let mut cur = state;
            while parent[cur] != usize::MAX {
                cur = parent[cur];
                path.push(cur / 2);
            }
            path.reverse();
            return Some(path);
        }
        let x = i % cols;
        let y = i / cols;
        for (nx, ny, axis) in [
            (x.wrapping_sub(1), y, 0),
            (x + 1, y, 0),
            (x, y.wrapping_sub(1), 1),
            (x, y + 1, 1),
        ] {
            if nx >= cols || ny >= grid.rows {
                continue;
            }
            let n = ny * cols + nx;
            if grid.blocked[n]
                || grid
                    .obstacles
                    .iter()
                    .any(|r| r.hits(grid.point(i), grid.point(n)))
            {
                continue;
            }
            let next = n * 2 + axis;
            let step = 10
                + if axis != dir { 24 } else { 0 }
                + traffic[n][axis] * 48
                + traffic[n][1 - axis] * 18;
            let ng = g + step;
            if ng < cost[next] {
                cost[next] = ng;
                parent[next] = state;
                heap.push(Reverse((ng + heuristic(n), ng, next)));
            }
        }
    }
    None
}

fn simplify(points: Vec<Point>) -> Vec<Point> {
    let mut simplified: Vec<Point> = Vec::new();
    for p in points {
        if simplified.last() == Some(&p) {
            continue;
        }
        // Remove collinear points without removing reversals.
        while simplified.len() >= 2 {
            let u = simplified[simplified.len() - 2];
            let v = simplified[simplified.len() - 1];
            let straight = (u.x == v.x && v.x == p.x && (v.y - u.y) * (p.y - v.y) >= 0.)
                || (u.y == v.y && v.y == p.y && (v.x - u.x) * (p.x - v.x) >= 0.);
            if !straight {
                break;
            }
            simplified.pop();
        }
        simplified.push(p);
    }
    simplified
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    // Check the JSON boundary and every actual segment, including both escape stubs.
    fn checked(input: Value) -> Vec<Vec<Point>> {
        let request: Request = serde_json::from_value(input.clone()).unwrap();
        let response: Value =
            serde_json::from_str(&route_impl(&input.to_string()).unwrap()).unwrap();
        let routes = response["routes"].as_array().unwrap();
        assert_eq!(routes.len(), request.wires.len());
        routes
            .iter()
            .zip(&request.wires)
            .map(|(route, wire)| {
                assert_eq!(route["id"], wire.id);
                let points: Vec<Point> = serde_json::from_value(route["points"].clone()).unwrap();
                if !points.is_empty() {
                    assert_eq!(points.first(), Some(&wire.source.point));
                    assert_eq!(points.last(), Some(&wire.target.point));
                    assert!(points[1].x > points[0].x);
                    assert_eq!(points[1].y, points[0].y);
                    let n = points.len();
                    assert!(points[n - 1].x > points[n - 2].x);
                    assert_eq!(points[n - 1].y, points[n - 2].y);
                    for segment in points.windows(2) {
                        let (a, b) = (segment[0], segment[1]);
                        assert!(a.x == b.x || a.y == b.y, "diagonal: {segment:?}");
                        for block in &request.blocks {
                            assert!(
                                !block.bounds(0.).crosses_interior(a, b),
                                "crosses {}: {segment:?}",
                                block.id
                            );
                        }
                    }
                }
                points
            })
            .collect()
    }

    fn scene() -> Value {
        json!({"blocks":[
            {"id":"a","x":0,"y":0,"width":120,"height":120},
            {"id":"b","x":480,"y":0,"width":120,"height":120}
        ],"wires":[{"id":"ab","source":{"owner":"a","x":120,"y":60},"target":{"owner":"b","x":480,"y":60}}]})
    }

    #[test]
    fn normal_link_keeps_exact_endpoints() {
        assert_eq!(
            checked(scene())[0],
            vec![Point { x: 120., y: 60. }, Point { x: 480., y: 60. }]
        );
    }

    #[test]
    fn middle_obstacle_and_variable_height_have_clearance() {
        for height in [120., 420.5] {
            let mut input = scene();
            for block in input["blocks"].as_array_mut().unwrap() {
                block["height"] = json!(height);
            }
            for endpoint in ["source", "target"] {
                input["wires"][0][endpoint]["y"] = json!(height - 60.25);
            }
            input["blocks"]
                .as_array_mut()
                .unwrap()
                .push(json!({"id":"middle","x":240,"y":0,"width":120,"height":height}));
            let points = &checked(input)[0];
            assert!(!points.is_empty());
            let obstacle = Rect {
                min: Point { x: 228., y: -12. },
                max: Point {
                    x: 372.,
                    y: height + 12.,
                },
            };
            assert!(points.windows(2).all(|s| !obstacle.hits(s[0], s[1])));
        }
    }

    #[test]
    fn congestion_diverts_later_wire_without_changing_endpoints() {
        let mut input = scene();
        let mut second = input["wires"][0].clone();
        second["id"] = json!("second");
        input["wires"].as_array_mut().unwrap().push(second);
        let routes = checked(input);
        assert_eq!(routes[0].len(), 2);
        assert!(!routes[1].is_empty());
        assert_ne!(routes[0], routes[1]);
    }

    #[test]
    fn reverse_feedback_and_self_loop_route_around_owners() {
        let mut reverse = scene();
        reverse["wires"][0]["source"] = json!({"owner":"b","x":600,"y":60});
        reverse["wires"][0]["target"] = json!({"owner":"a","x":0,"y":60});
        assert!(!checked(reverse.clone())[0].is_empty());
        reverse["wires"][0]["target"] = json!({"owner":"b","x":480,"y":60});
        assert!(!checked(reverse)[0].is_empty());
    }

    #[test]
    fn fractional_off_grid_endpoints_keep_orthogonal_bridges() {
        let mut input = scene();
        input["blocks"][0] = json!({"id":"a","x":0.25,"y":0.5,"width":120.5,"height":137.25});
        input["blocks"][1] =
            json!({"id":"b","x":480.125,"y":-12.75,"width":119.75,"height":209.125});
        input["wires"][0]["source"] = json!({"owner":"a","x":120.75,"y":61.25});
        input["wires"][0]["target"] = json!({"owner":"b","x":480.125,"y":77.875});
        input["blocks"]
            .as_array_mut()
            .unwrap()
            .push(json!({"id":"thin","x":243.25,"y":20.5,"width":0.5,"height":81.25}));
        assert!(!checked(input)[0].is_empty());
    }

    #[test]
    fn measured_fractional_boundaries_tolerate_rounding_but_not_interior_endpoints() {
        let mut input = scene();
        input["blocks"][0] = json!({"id":"a","x":0.3,"y":0.2,"width":240.4,"height":180.9});
        input["blocks"][1] = json!({"id":"b","x":480.1,"y":0.2,"width":240.2,"height":247.3});
        let right = 480.1 + 240.2;
        for source_offset in [-1e-5, 0., 1e-5] {
            for target_offset in [-1e-5, 0., 1e-5] {
                input["wires"][0]["source"] =
                    json!({"owner":"b","x":right + source_offset,"y":60.7});
                input["wires"][0]["target"] = json!({"owner":"a","x":0.3 + target_offset,"y":72.9});
                assert!(!checked(input.clone())[0].is_empty());
            }
        }
        input["wires"][0]["source"]["x"] = json!(right - 0.01);
        assert!(checked(input.clone())[0].is_empty());
        input["wires"][0]["source"]["x"] = json!(right);
        input["wires"][0]["target"]["x"] = json!(0.3 + 0.01);
        assert!(checked(input)[0].is_empty());
    }

    #[test]
    fn duplicate_and_zero_length_segments_keep_collision_checks() {
        let boundary = Point { x: 120., y: 60. };
        let outside = Point { x: 144., y: 60. };
        assert_eq!(simplify(vec![boundary; 3]), vec![boundary]);
        assert_eq!(
            simplify(vec![
                boundary, boundary, outside, outside, boundary, boundary
            ]),
            vec![boundary, outside, boundary]
        );
        let request: Request = serde_json::from_value(scene()).unwrap();
        assert!(stub_clear(&[boundary; 2], "a", &request.blocks));
        let inside = Point { x: 119., y: 60. };
        assert!(!stub_clear(&[inside; 2], "a", &request.blocks));
        // Other inflated bounds remain inclusive, even for a zero-length segment.
        let other_clearance = Point { x: 468., y: 60. };
        assert!(!stub_clear(&[other_clearance; 2], "a", &request.blocks));
    }

    #[test]
    fn overlapping_and_trapped_stubs_fail_without_omitting_wires() {
        let mut input = scene();
        input["blocks"]
            .as_array_mut()
            .unwrap()
            .push(json!({"id":"trap","x":125,"y":0,"width":20,"height":120}));
        input["wires"].as_array_mut().unwrap().push(json!({"id":"missing-owner","source":{"owner":"missing","x":120,"y":60},"target":{"owner":"b","x":480,"y":60}}));
        assert!(checked(input).iter().all(Vec::is_empty));
        let mut overlap = scene();
        overlap["blocks"][1]["x"] = json!(60);
        overlap["wires"][0]["target"]["x"] = json!(60);
        assert!(checked(overlap)[0].is_empty());
    }

    #[test]
    fn geometry_beyond_old_world_bounds_and_at_negative_positions() {
        for (dx, dy) in [(5000., 3200.), (-4200.25, -2800.5)] {
            let mut input = scene();
            for block in input["blocks"].as_array_mut().unwrap() {
                block["x"] = json!(block["x"].as_f64().unwrap() + dx);
                block["y"] = json!(block["y"].as_f64().unwrap() + dy);
            }
            for endpoint in ["source", "target"] {
                let p = &mut input["wires"][0][endpoint];
                p["x"] = json!(p["x"].as_f64().unwrap() + dx);
                p["y"] = json!(p["y"].as_f64().unwrap() + dy);
            }
            assert!(!checked(input)[0].is_empty());
        }
    }

    #[test]
    fn full_segment_collision_rejects_between_node_obstacle() {
        // A thin off-grid rectangle lies between two grid nodes. Both nodes are free.
        let grid = Grid {
            origin: Point { x: 0., y: 0. },
            cols: 2,
            rows: 1,
            obstacles: vec![Rect {
                min: Point { x: 5.25, y: -1. },
                max: Point { x: 5.5, y: 1. },
            }],
            blocked: vec![false; 2],
        };
        assert!(search(0, 1, &grid, &[[0; 2]; 2]).is_none());
        let block = Block {
            id: "other".into(),
            x: 5.25,
            y: 0.,
            width: 0.25,
            height: 1.,
        };
        assert!(!stub_clear(
            &[Point { x: -6., y: -20. }, Point { x: -6., y: 20. }],
            "owner",
            &[
                Block {
                    id: "owner".into(),
                    x: -100.,
                    y: -100.,
                    width: 1.,
                    height: 1.
                },
                block
            ]
        ));
    }

    #[test]
    fn empty_input_and_malformed_json_are_explicit() {
        assert!(checked(json!({"blocks":[],"wires":[]})).is_empty());
        assert!(route_impl("not json").is_err());
        assert!(route_impl(r#"{"blocks":[],"wires":[{"id":"invalid"}]}"#).is_err());
    }
}
